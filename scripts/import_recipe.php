<?php
/**
 * import_recipe.php
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
const SCRIPT_PATH = '/path/to/recipe_to_jsonld.py';

// If you've already run the script once with --use-venv, point this at
// that venv's own interpreter instead of the system one -- avoids
// depending on recipe-scrapers/bs4 being installed system-wide. Falls
// back to plain "python3" if you haven't set up a venv.
// IMPORTANT: never pass --use-venv itself on this hot path -- it
// re-installs/upgrades dependencies on every invocation (a network call
// each time), meant for manual one-time setup, not a live web request.
$venvPython = getenv('HOME') . '/.cache/recipe_to_jsonld/venv/bin/python3';
$pythonBin = is_executable($venvPython) ? $venvPython : 'python3';

// Scraping a live page can take a few seconds on a slow source site;
// make sure PHP itself doesn't time this request out early.
set_time_limit(60);

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
    http_response_code(502);
    echo json_encode([
        'error' => 'Could not extract a recipe from that URL',
        'details' => $stderr !== '' ? trim($stderr) : 'Unknown error',
    ]);
    exit;
}

$recipeJson = json_decode($stdout, true);
if ($recipeJson === null) {
    http_response_code(502);
    echo json_encode(['error' => 'Import script returned unparseable output']);
    exit;
}

http_response_code(200);
echo json_encode(['status' => 'ok', 'recipe' => $recipeJson]);
