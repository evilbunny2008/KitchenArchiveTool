<?php
/**
 * get_jsonld.php
 *
 * Accepts a recipe URL from the app, runs the existing recipe_to_jsonld.py
 * (already in the KitchenArchiveTool repo) to scrape+convert the page, and
 * returns the resulting JSON-LD as the response. Uploading that JSON-LD
 * into the user's own Nextcloud Cookbook is the app's job, not this
 * service's -- see CookbookAPI.createRecipe() in KAT itself, called using
 * whichever account the app is already authenticated as via Nextcloud's
 * own SSO.
 *
 * WHY THIS SHAPE: earlier versions of this bridge accepted a Nextcloud
 * hostname/username/app-password here too, and uploaded directly on the
 * app's behalf. That worked, but meant this server needed *some* form of
 * every user's Nextcloud credentials to do its job, however carefully
 * that was handled in transit (see the git history of this file for that
 * version's stdin-based approach). This version needs none of that at
 * all: it never receives, stores, or forwards a single Nextcloud
 * credential for any user, ever. It's a pure "URL in, recipe JSON out"
 * service -- nothing here can act on anyone's Nextcloud account, because
 * it never has anything to act with.
 *
 * SECURITY DESIGN (still relevant, even with the credential handling gone):
 * This never builds a shell command *string* from user input and tries to
 * clean it -- that's the failure-prone pattern ("sanitization" as a
 * blacklist of dangerous characters can always miss one). Instead
 * proc_open() is called with the command as an *array*: each argument
 * reaches the Python process directly via exec(), with no shell in
 * between to reinterpret it. A value like `; rm -rf /` in the URL just
 * becomes a literal, inert argument.
 */

header('Content-Type: application/json');

// --- Configuration -------------------------------------------------------
// Path to recipe_to_jsonld.py. Keep it outside the web root if it isn't
// already, so it can't be requested directly over HTTP.
const SCRIPT_PATH = '/var/www/bin/recipe_to_jsonld.py';

// Defines NOTIFY_EMAIL. Kept in its own file, outside version control (see
// email_config.sample.php and .gitignore), so the notification address can
// be set on this server without needing to modify anything checked into
// GitHub. require (not include) is deliberate: this fails loudly if
// email_config.php hasn't been created yet, rather than silently running
// with no notifications configured.
require __DIR__ . '/email_config.php';

// If you've already run the script once with --use-venv, point this at
// that venv's own interpreter instead of the system one -- avoids
// depending on recipe-scrapers/bs4 being installed system-wide. Falls
// back to plain "python3" if you haven't set up a venv.
// IMPORTANT: never pass --use-venv itself on this hot path -- it
// re-installs/upgrades dependencies on every invocation (a network call
// each time), meant for manual one-time setup, not a live web request.
$venvPython = '/var/www/.cache/recipe_to_jsonld/venv/bin/python3';
$pythonBin = is_executable($venvPython) ? $venvPython : 'python3';

// Scraping a live page can take a few seconds on a slow source site;
// make sure PHP itself doesn't time this request out early.
set_time_limit(60);

/**
 * Emails NOTIFY_EMAIL about a recipe that failed to convert cleanly.
 * Uses PHP's built-in mail() -- the simplest option with no extra
 * dependencies, but it only actually delivers anything if this server
 * has a working MTA configured (sendmail/postfix/exim, or php.ini's
 * sendmail_path pointed at one) -- common on a VPS/dedicated box you
 * administer yourself, not guaranteed on shared hosting. If mail() turns
 * out not to deliver reliably here, swap this for an authenticated SMTP
 * library (e.g. PHPMailer) or a transactional email API instead; this
 * function's signature wouldn't need to change either way.
 *
 * Deliberately no rate-limiting/deduplication -- every failure gets its
 * own email. Worth revisiting if a broader outage (e.g. a source site
 * blocking this server's requests) ever makes that noisy.
 */
function notify_conversion_failure(string $recipeUrl, string $reason, string $details): void {
    if (NOTIFY_EMAIL === '') {
        return;
    }

    $subject = 'get_jsonld.php: recipe conversion failed';
    $body = "URL: $recipeUrl\n"
        . "Reason: $reason\n"
        . 'Time: ' . date('c') . "\n"
        . 'User-Agent: ' . ($_SERVER['HTTP_USER_AGENT'] ?? '(none)') . "\n"
        . "\nDetails:\n$details\n";

    // Suppress mail()'s own warning on failure (e.g. no MTA configured)
    // rather than letting it leak into the JSON response below -- a
    // failed notification shouldn't turn into a second, unrelated error
    // for the person who just wanted to import a recipe.
    @mail(NOTIFY_EMAIL, $subject, $body);
}

// --- 1. Only accept POST -----------------------------------------------
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed, use POST']);
    exit;
}

// --- 2. Read and validate input -----------------------------------------
$recipeUrl = $_POST['recipe_url'] ?? '';

// Real validation, not shell-safety sanitization: reject anything that
// isn't a well-formed http(s) URL up front, since it's about to be
// fetched by the Python script and a malformed value should fail fast
// with a clear error here rather than confusingly partway through.
if ($recipeUrl === '' || !filter_var($recipeUrl, FILTER_VALIDATE_URL)) {
    http_response_code(400);
    echo json_encode(['error' => 'recipe_url must be a valid URL']);
    exit;
}
$scheme = parse_url($recipeUrl, PHP_URL_SCHEME);
if (!in_array($scheme, ['http', 'https'], true)) {
    http_response_code(400);
    echo json_encode(['error' => 'recipe_url must use http or https']);
    exit;
}

// --- 3. Run recipe_to_jsonld.py ------------------------------------------
// Array form: no shell is invoked to parse this, so nothing here needs
// escaping. --json-only prints just the recipe JSON to stdout -- no HTML
// wrapper, no upload attempt, nothing else on stdout to strip out.
$command = [$pythonBin, SCRIPT_PATH, '--url', $recipeUrl, '--json-only'];

$descriptorSpec = [
    0 => ['pipe', 'r'],
    1 => ['pipe', 'w'],  // stdout -- the recipe JSON itself, and nothing else
    2 => ['pipe', 'w'],  // stderr -- diagnostics (which strategy found the recipe, any warnings)
];

$process = proc_open($command, $descriptorSpec, $pipes);

if (!is_resource($process)) {
    http_response_code(500);
    echo json_encode(['error' => 'Failed to start import process']);
    exit;
}

fclose($pipes[0]); // nothing to send on stdin -- this script takes no credentials anymore
$stdout = stream_get_contents($pipes[1]);
$stderr = stream_get_contents($pipes[2]);
fclose($pipes[1]);
fclose($pipes[2]);

$exitCode = proc_close($process);

// --- 4. Respond ------------------------------------------------------------
if ($exitCode !== 0) {
    $details = $stderr !== '' ? trim($stderr) : 'Unknown error';
    notify_conversion_failure($recipeUrl, "recipe_to_jsonld.py exited with status $exitCode", $details);
    http_response_code(502);
    echo json_encode([
        'error' => 'Could not extract a recipe from that URL',
        'details' => $details,
    ]);
    exit;
}

$recipeJson = json_decode($stdout, true);
if ($recipeJson === null) {
    notify_conversion_failure(
        $recipeUrl,
        'recipe_to_jsonld.py exited 0 but stdout was not valid JSON',
        "stdout:\n$stdout\n\nstderr:\n$stderr"
    );
    http_response_code(502);
    echo json_encode(['error' => 'Import script returned unparseable output']);
    exit;
}

http_response_code(200);
echo json_encode(['status' => 'ok', 'recipe' => $recipeJson]);
