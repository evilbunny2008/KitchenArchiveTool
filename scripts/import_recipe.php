<?php
/**
 * import_recipe.php
 *
 * Accepts a Nextcloud hostname/username/password and a recipe URL from
 * the app, validates them, and runs the existing recipe_to_jsonld.py
 * (already in the KitchenArchiveTool repo) to scrape+convert the page and
 * upload the result straight into that Nextcloud instance's Cookbook.
 *
 * SECURITY DESIGN:
 * This never builds a shell command *string* from user input and tries to
 * clean it -- that's the failure-prone pattern ("sanitization" as a
 * blacklist of dangerous characters can always miss one). Instead
 * proc_open() is called with the command as an *array*: each argument
 * reaches the Python process directly via exec(), with no shell in
 * between to reinterpret it. A value like `; rm -rf /` in the URL just
 * becomes a literal, inert argument.
 *
 * The password specifically never becomes a process argument at all --
 * it's written to the child process's stdin instead, using the
 * --nextcloud-pass-stdin flag now supported by recipe_to_jsonld.py.
 * Process command-line arguments are visible to every other local user on
 * this server via `ps aux` / /proc/<pid>/cmdline for as long as the
 * process runs; a pipe isn't. The username still goes on the command
 * line -- lower sensitivity, and recipe_to_jsonld.py doesn't offer a
 * stdin alternative for it.
 *
 * The hostname is accepted from the client (not a fixed server-side
 * constant): the app this talks to supports multiple Nextcloud accounts
 * across different servers, so the bridge needs to as well. Worth being
 * aware of the tradeoff this reopens versus a single fixed instance: this
 * endpoint will now attempt Basic Auth against *any* hostname a caller
 * supplies, which is only actually useful to someone who already has a
 * valid username+app-password for that host -- it can't do anything
 * without real credentials -- but it does mean this server will make
 * outbound requests to hosts of a caller's choosing. If this bridge is
 * ever exposed beyond your own app, consider an allow-list of hostnames
 * here.
 */

header('Content-Type: application/json');

// --- Configuration -------------------------------------------------------
// Path to recipe_to_jsonld.py. Keep it outside the web root if it isn't
// already, so it can't be requested directly over HTTP.
const SCRIPT_PATH = '/path/to/recipe_to_jsonld.py';

// If you've already run the script once with --use-venv, point this at
// that venv's own interpreter instead of the system one -- avoids
// depending on recipe-scrapers/bs4/nextcloud-cookbook-api being installed
// system-wide. Falls back to plain "python3" if you haven't set up a venv.
// IMPORTANT: never pass --use-venv itself on this hot path -- it
// re-installs/upgrades dependencies on every invocation (a network call
// each time), meant for manual one-time setup, not a live web request.
$venvPython = getenv('HOME') . '/.cache/recipe_to_jsonld/venv/bin/python3';
$pythonBin = is_executable($venvPython) ? $venvPython : 'python3';

// Scraping a live page + uploading can take a few seconds on a slow
// source site; make sure PHP itself doesn't time this request out early.
set_time_limit(60);

// --- 1. Only accept POST -----------------------------------------------
if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['error' => 'Method not allowed, use POST']);
    exit;
}

// --- 2. Read and validate input -----------------------------------------
$hostname = $_POST['hostname'] ?? '';
$username = $_POST['username'] ?? '';
$password = $_POST['password'] ?? '';
$recipeUrl = $_POST['recipe_url'] ?? '';

$errors = [];

if ($hostname === '' || !filter_var($hostname, FILTER_VALIDATE_URL)) {
    $errors[] = 'hostname must be a valid URL';
} else {
    $hostnameScheme = parse_url($hostname, PHP_URL_SCHEME);
    if (!in_array($hostnameScheme, ['http', 'https'], true)) {
        $errors[] = 'hostname must use http or https';
    }
}

if ($username === '' || mb_strlen($username) > 256) {
    $errors[] = 'username is required and must be under 256 characters';
}
if ($password === '' || mb_strlen($password) > 1024) {
    $errors[] = 'password is required and must be under 1024 characters';
}

// This is real validation, not shell-safety sanitization: reject anything
// that isn't a well-formed http(s) URL up front, since it's about to be
// fetched by the Python script and a malformed value should fail fast
// with a clear error here rather than confusingly partway through.
if ($recipeUrl === '' || !filter_var($recipeUrl, FILTER_VALIDATE_URL)) {
    $errors[] = 'recipe_url must be a valid URL';
} else {
    $scheme = parse_url($recipeUrl, PHP_URL_SCHEME);
    if (!in_array($scheme, ['http', 'https'], true)) {
        $errors[] = 'recipe_url must use http or https';
    }
}

if (!empty($errors)) {
    http_response_code(400);
    echo json_encode(['error' => 'Invalid input', 'details' => $errors]);
    exit;
}

// --- 3. Run recipe_to_jsonld.py ------------------------------------------
// Array form: no shell is invoked to parse this, so nothing here needs
// escaping. Deliberately NOT passing --use-venv -- see the note above.
// --nextcloud-pass-stdin (not --nextcloud-pass) keeps the password off
// the command line entirely -- it's written to the child's stdin below.
$command = [
    $pythonBin,
    SCRIPT_PATH,
    '--url', $recipeUrl,
    '--nextcloud-url', $hostname,
    '--nextcloud-user', $username,
    '--nextcloud-pass-stdin',
];

$descriptorSpec = [
    0 => ['pipe', 'r'],
    1 => ['pipe', 'w'],  // stdout -- "Created recipe 'X' with ID: 123" on success
    2 => ['pipe', 'w'],  // stderr -- the script's own error messages (auth failure, duplicate name, etc.)
];

$process = proc_open($command, $descriptorSpec, $pipes);

if (!is_resource($process)) {
    http_response_code(500);
    echo json_encode(['error' => 'Failed to start import process']);
    exit;
}

// The script does readline() on stdin (see --nextcloud-pass-stdin), so a
// trailing newline is expected, not just tolerated.
fwrite($pipes[0], $password . "\n");
fclose($pipes[0]);
$stdout = stream_get_contents($pipes[1]);
$stderr = stream_get_contents($pipes[2]);
fclose($pipes[1]);
fclose($pipes[2]);

$exitCode = proc_close($process);

// --- 4. Respond ------------------------------------------------------------
if ($exitCode !== 0) {
    http_response_code(502);
    echo json_encode([
        'error' => 'Import failed',
        // recipe_to_jsonld.py's own stderr messages are already
        // user-actionable (e.g. "2FA is enabled, use an app password" or
        // "a recipe with this name already exists") -- worth relaying
        // directly rather than replacing with a generic message.
        'details' => $stderr !== '' ? trim($stderr) : 'Unknown error',
    ]);
    exit;
}

// Success message looks like: Created recipe 'Name' with ID: 123
$recipeId = null;
if (preg_match('/with ID:\s*(\S+)/', $stdout, $matches)) {
    $recipeId = $matches[1];
}

http_response_code(200);
echo json_encode([
    'status' => 'ok',
    'recipe_id' => $recipeId,
    'message' => trim($stdout),
]);
