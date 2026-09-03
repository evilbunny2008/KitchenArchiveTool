#!/usr/bin/python3

"""
Convert recipe web pages into schema.org Recipe JSON-LD using the
`recipe-scrapers` library and optionally upload to a Nextcloud Cookbook instance.

Install:
    pip install recipe-scrapers
    pip install requests
        Only needed for --nextcloud-url uploads; almost certainly already
        installed as a dependency of nextcloud-cookbook-api regardless.

Usage:
    python recipe_to_jsonld.py --url "https://example.com/some-recipe"
    python recipe_to_jsonld.py --url "https://example.com/some-recipe" --nextcloud-url "https://nextcloud.example.com" --nextcloud-user "myuser" --nextcloud-pass "myapppassword"
        --nextcloud-pass must be a Nextcloud app password, not the account's
        normal login password, if 2-factor authentication is enabled on
        that account (the regular password is rejected outright in that
        case). Generate one at: Nextcloud web UI -> Settings -> Security ->
        Devices & sessions -> "Create new app password". Works identically
        even without 2FA enabled, so it's the safer default either way --
        it can be revoked independently of the account's real password.
    python recipe_to_jsonld.py --file saved_page.html --url "https://example.com/some-recipe" --out output.html
    python recipe_to_jsonld.py --use-venv --file saved_page.html
        Creates (or reuses) a dedicated virtual environment for this
        script's own dependencies and upgrades them there, then re-runs
        the rest of the command inside it. Useful on Debian/Ubuntu, where
        a plain "pip install --upgrade recipe-scrapers" is blocked outside
        a venv by PEP 668 ("externally managed environment"). Never
        touches system/apt-managed Python packages. Safe to include on
        every invocation -- after the first run it just re-upgrades and
        reuses the same venv rather than recreating it.
"""

import os
import subprocess
import sys

# --- Optional self-managed virtual environment bootstrap -------------------
#
# This has to run, and decide whether to re-exec, before the rest of this
# file's imports below -- recipe_scrapers/bs4/nextcloud_cookbook_api are
# exactly the packages --use-venv might need to install, so they can't be
# imported yet if this script is running under the system Python and those
# packages are missing or too old there.

VENV_DIR = os.path.join(os.path.expanduser("~"), ".cache", "recipe_to_jsonld", "venv")
VENV_ACTIVE_ENV_VAR = "RECIPE_TO_JSONLD_VENV_ACTIVE"
REQUIRED_PACKAGES = ["recipe-scrapers", "beautifulsoup4", "nextcloud-cookbook-api", "requests"]


def _bootstrap_venv_and_reexec():
    """
    Creates the dedicated virtual environment at VENV_DIR if it doesn't
    already exist, installs/upgrades this script's dependencies inside it
    (always "--upgrade", so re-running --use-venv later picks up newer
    releases too, not just a one-time install), then replaces the current
    process with that venv's Python running this same script and the same
    arguments (minus --use-venv itself, so the re-launched process
    doesn't loop back into this bootstrap again).
    """
    venv_python = os.path.join(VENV_DIR, "bin", "python3")

    if not os.path.exists(venv_python):
        print(f"Creating a dedicated virtual environment at {VENV_DIR} ...", file=sys.stderr)
        subprocess.run([sys.executable, "-m", "venv", VENV_DIR], check=True)

    print(f"Installing/upgrading dependencies in the virtual environment "
          f"({', '.join(REQUIRED_PACKAGES)}) ...", file=sys.stderr)
    subprocess.run(
        [venv_python, "-m", "pip", "install", "--upgrade", "--quiet"] + REQUIRED_PACKAGES,
        check=True,
    )

    remaining_args = [a for a in sys.argv[1:] if a != "--use-venv"]
    print("Re-launching inside the virtual environment...\n", file=sys.stderr)
    env = dict(os.environ, **{VENV_ACTIVE_ENV_VAR: "1"})
    os.execve(venv_python, [venv_python, os.path.abspath(__file__)] + remaining_args, env)


if "--use-venv" in sys.argv[1:] and os.environ.get(VENV_ACTIVE_ENV_VAR) != "1":
    try:
        _bootstrap_venv_and_reexec()
    except (subprocess.CalledProcessError, OSError) as e:
        print(f"Virtual environment setup failed: {e}", file=sys.stderr)
        sys.exit(1)

# --- end bootstrap ---

import argparse
import base64
import json
import re
from datetime import datetime, timezone

from bs4 import BeautifulSoup

from nextcloud_cookbook_api.models import Nutrition, Recipe

from recipe_scrapers import scrape_html
from recipe_scrapers._exceptions import RecipeScrapersExceptions

import requests
from urllib.error import URLError, HTTPError
from urllib.parse import urljoin
from urllib.request import Request, urlopen

USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)

# Rough patterns for splitting an ingredient line into distinct ingredients.
# Old-style recipe pages (no markup) often jam several ingredients into one
# paragraph or table cell, separated by line breaks that get flattened to
# whitespace, or by a quantity+unit pattern repeating inline.
UNIT_PATTERN = re.compile(
    r"(?<![/\d])(?=(?:\d+[\d\s/.,]*|[\u00bd\u00bc\u00be\u2153\u2154\u215b\u215c\u215d\u215e])"
    r"\s*(?:g|kg|ml|l|tsp|tbsp|tbs|cup|cups|oz|lb|lbs)\b)",
    re.IGNORECASE,
)

# Filenames/paths that usually indicate a non-content image: nav icons,
# ads, tracking pixels, logos, spacers, social share buttons, etc.
IMAGE_SKIP_PATTERN = re.compile(
    r"(logo|icon|spacer|pixel|blank|banner|advert|sprite|button|social|"
    r"share|avatar|placeholder|badge|rating-star|arrow|nav[-_]|header|footer)",
    re.IGNORECASE,
)
IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp", ".gif")


def iso8601_duration(minutes):
    """Convert an integer number of minutes into an ISO 8601 duration (e.g. PT35M)."""
    if minutes is None:
        return None
    try:
        minutes = int(minutes)
    except (TypeError, ValueError):
        return None
    hours, mins = divmod(minutes, 60)
    parts = "PT"
    if hours:
        parts += f"{hours}H"
    if mins or not hours:
        parts += f"{mins}M"
    return parts


FRACTION_MAP = {
    "\u00bc": "1/4",
    "\u00bd": "1/2",
    "\u00be": "3/4",
    "\u2150": "1/7",
    "\u2151": "1/9",
    "\u2152": "1/10",
    "\u2153": "1/3",
    "\u2154": "2/3",
    "\u2155": "1/5",
    "\u2156": "2/5",
    "\u2157": "3/5",
    "\u2158": "4/5",
    "\u2159": "1/6",
    "\u215a": "5/6",
    "\u215b": "1/8",
    "\u215c": "3/8",
    "\u215d": "5/8",
    "\u215e": "7/8",
}
FRACTION_PATTERN = re.compile(r"(\d)?(" + "|".join(FRACTION_MAP.keys()) + ")")


def normalize_fractions(text):
    """Replace vulgar fraction symbols (e.g. ¼, ½, ¾) with plain ASCII
    equivalents (1/4, 1/2, 3/4). If the symbol directly follows a whole
    number (e.g. "1½"), a space is inserted so it reads as "1 1/2"."""
    if not text:
        return text

    text = re.sub(
        r"(\d+(?:\.\d+)?\s*(?:g|kg|ml|l|cm))\s*"
        r"\([^)]*\b(?:cup|cups|tbsp|tbs|tablespoons?|tsp|teaspoons?|oz|ounces?|lb|lbs|pounds?|inch|inches|in)\b[^)]*\)",
        r"\1",
        text,
        flags=re.IGNORECASE,
    )

    def repl(match):
        whole, frac_char = match.group(1), match.group(2)
        frac = FRACTION_MAP[frac_char]
        return f"{whole} {frac}" if whole else frac

    return FRACTION_PATTERN.sub(repl, text)


def normalize_fractions_deep(obj):
    """Recursively apply normalize_fractions to every string value in a
    JSON-like structure (dict/list/str), leaving other types untouched."""
    if isinstance(obj, str):
        return normalize_fractions(obj)
    if isinstance(obj, dict):
        return {k: normalize_fractions_deep(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [normalize_fractions_deep(v) for v in obj]
    return obj


# --- Imperial -> metric normalization -------------------------------------
#
# Scope, deliberately: weight (oz/lb <-> g/kg), length (in/inch <-> cm/mm),
# volume (cup/tbsp/tsp <-> ml), and oven temperature (°F <-> °C). Cup and
# tbsp/tsp conversions use fixed volumetric factors (not ingredient-density-
# aware), so a cup of flour and a cup of milk convert identically -- fine
# for consistency, imprecise for baking accuracy.

QTY_TOKEN = r"\d+\s\d+/\d+|\d+/\d+|\d+(?:\.\d+)?"

METRIC_UNITS = r"(?:kilograms?|kg|grams?|g|millilit(?:re|er)s?|ml|lit(?:re|er)s?|l|centimet(?:re|er)s?|cm|millimet(?:re|er)s?|mm)"
# Word forms of inch need a trailing \b (so "in" doesn't match inside "into");
# the " symbol is unambiguous on its own and can't take a trailing \b at all,
# since it's a non-word character -- \b never matches between two non-word
# characters (e.g. between " and a following space or close-paren). Each
# pattern below therefore keeps the word units under \b and adds the "
# symbol as a separate, boundary-free alternative.
INCH_WORDS = r"(?:inches|inch|in)"
INCH_SYMBOL = r"\""
# Fluid ounce is a volume unit (~29.6ml), distinct from plain "oz" (a
# weight unit, ~28.3g) -- checked as its own alternative (not derived from
# "oz") so "16floz" matches as one complete unit token rather than as
# digits+"oz" with a stray leading "fl" left dangling.
FLOZ_UNITS = r"(?:fl\.?\s*oz\.?|fluid\s*ounces?|floz)"
IMPERIAL_UNITS_CHAIN = rf"(?:pounds?|lbs?|{FLOZ_UNITS}|ounces?|oz|cups?|{INCH_WORDS}|tablespoons?|tbsp|tbs|teaspoons?|tsp)"
IMPERIAL_UNITS_STANDALONE = rf"(?:pounds?|lbs?|{FLOZ_UNITS}|ounces?|oz|cups?|inches|inch|tablespoons?|tbsp|tbs|teaspoons?|tsp)"

_CHAIN_TOKEN = rf"(?:{QTY_TOKEN})\s?(?:(?:{METRIC_UNITS}|{IMPERIAL_UNITS_CHAIN})(?![A-Za-z])|{INCH_SYMBOL})"
_CHAIN_TOKEN_RE = re.compile(_CHAIN_TOKEN, re.IGNORECASE)
CHAIN_RE = re.compile(rf"{_CHAIN_TOKEN}(?:\s*/\s*{_CHAIN_TOKEN})+", re.IGNORECASE)
STANDALONE_RE = re.compile(rf"(?:{QTY_TOKEN})\s?(?:{IMPERIAL_UNITS_STANDALONE}(?![A-Za-z])|{INCH_SYMBOL})", re.IGNORECASE)

_TOKEN_SPLIT_RE = re.compile(rf"^({QTY_TOKEN})\s?({METRIC_UNITS}|{IMPERIAL_UNITS_CHAIN}|{INCH_SYMBOL})$", re.IGNORECASE)
_STANDALONE_SPLIT_RE = re.compile(rf"^({QTY_TOKEN})\s?({IMPERIAL_UNITS_STANDALONE}|{INCH_SYMBOL})$", re.IGNORECASE)

TEMP_C = r"(\d+)\s*(?:\u00b0|degrees?)?\s*C\b"
TEMP_F = r"(\d+)\s*(?:\u00b0|degrees?)?\s*F\b"
CF_PAIR_RE = re.compile(rf"{TEMP_C}\s*[/(]\s*{TEMP_F}\)?")
FC_PAIR_RE = re.compile(rf"{TEMP_F}\s*[/(]\s*{TEMP_C}\)?")
LONE_F_WITH_SEP_RE = re.compile(rf"(?:\s*[/(]\s*)?{TEMP_F}\)?")


def parse_quantity(qty_str):
    """Parse '6', '1 1/2', or '3/4' into a float. Returns None for anything
    unparseable (e.g. ranges like '6-8'), so the caller can leave those alone."""
    qty_str = qty_str.strip()
    m = re.match(r"^(\d+)\s(\d+)/(\d+)$", qty_str)
    if m:
        return int(m.group(1)) + int(m.group(2)) / int(m.group(3))
    m = re.match(r"^(\d+)/(\d+)$", qty_str)
    if m:
        return int(m.group(1)) / int(m.group(2))
    try:
        return float(qty_str)
    except ValueError:
        return None


def _clean_number(value):
    """Returns value as a plain int if it has no fractional part (e.g. 5.0
    -> 5), otherwise leaves it as-is. round(x*2)/2 (used below for
    'nearest 0.5' rounding) always returns a float in Python even when the
    result is a whole number, which without this produces ugly output like
    '5.0g' or '5.0cm' instead of the clean '5g'/'5cm' a whole-number
    quantity should read as."""
    return int(value) if value == int(value) else value


def round_metric(value, unit):
    """Round a converted metric value to something recipe-realistic rather
    than a long decimal. Small quantities keep one decimal place so, e.g.,
    a converted 1/4 tsp doesn't collapse to '0g'/'1g' and lose its meaning."""
    if unit in ("g", "ml"):
        if value >= 100:
            return int(round(value / 5) * 5)
        if value >= 5:
            return int(round(value))
        return _clean_number(round(value * 2) / 2)  # nearest 0.5
    if unit in ("kg", "l"):
        return round(value, 2)
    if unit == "cm":
        return _clean_number(round(value * 2) / 2)  # nearest 0.5 cm
    return round(value, 1)


LIQUID_KEYWORDS = (
    "oil", "milk", "water", "juice", "vinegar", "wine", "beer", "stock", "broth",
    "cream", "syrup", "sauce", "extract", "honey", "buttermilk", "yogurt", "yoghurt",
    "molasses", "treacle", "liqueur", "brandy", "rum", "vanilla",
)


LIQUID_KEYWORD_PATTERN = re.compile(
    r"\b(?:" + "|".join(re.escape(kw) for kw in LIQUID_KEYWORDS) + r")\b", re.IGNORECASE
)


def is_liquid_ingredient(context_text):
    """Best-effort check of whether an ingredient line describes a liquid
    (oil, milk, stock, etc.), based on keywords in its text. Used to decide
    whether a tsp/tbsp/cup measurement converts to ml (liquid) or g (dry).

    Matches on whole words only -- a naive substring check would (and
    once did) match "rum" inside "crumbs", misclassifying breadcrumbs (and
    anything else in that same ingredient string, e.g. a pinch of salt
    alongside them) as a liquid and converting them to ml instead of g."""
    if not context_text:
        return False
    return bool(LIQUID_KEYWORD_PATTERN.search(context_text))


# Rough weight-per-cup for common dry ingredients (grams). "Cup" is a volume
# unit, so a true weight conversion depends entirely on what's being
# measured -- a cup of packed leafy herbs weighs a fraction of a cup of
# grated cheese or granulated sugar. These are ballpark kitchen-reference
# figures, not exact for any specific product/brand.
CUP_WEIGHT_TABLE = [
    (("basil", "parsley", "cilantro", "coriander leaves", "mint", "spinach",
      "arugula", "rocket", "lettuce", "fresh herbs"), 30),
    (("cheese", "mozzarella", "cheddar", "parmesan", "feta"), 110),
    (("olives",), 150),
    (("flour",), 120),
    (("brown sugar",), 220),
    (("sugar",), 200),
    (("rice",), 185),
    (("oats",), 90),
    (("breadcrumbs", "panko"), 60),
    (("nuts", "almonds", "walnuts", "pecans", "cashews", "pine nuts"), 120),
    (("butter",), 227),
]
DEFAULT_CUP_WEIGHT_G = 120  # generic fallback for unmatched dry ingredients


def estimate_cup_weight_grams(qty, context):
    """Look up a rough grams-per-cup figure based on ingredient keywords in
    the surrounding context text, falling back to a generic default."""
    lower = (context or "").lower()
    for keywords, grams_per_cup in CUP_WEIGHT_TABLE:
        if any(kw in lower for kw in keywords):
            return qty * grams_per_cup
    return qty * DEFAULT_CUP_WEIGHT_G


def convert_imperial_token(qty_str, unit, context=""):
    """Convert a single imperial quantity+unit to its metric equivalent
    string. Returns the original text unchanged if the quantity can't be
    parsed (e.g. a range like '6-8 oz'). `context` is the surrounding
    ingredient text, used only to decide ml-vs-g for tsp/tbsp/cup."""
    original = f"{qty_str} {unit}".strip()
    qty = parse_quantity(qty_str)
    if qty is None:
        return original

    unit_l = unit.lower()
    if re.fullmatch(FLOZ_UNITS, unit_l, re.IGNORECASE):
        ml = qty * 29.5735
        return f"{round_metric(ml, 'ml')}ml"
    if unit_l in ("oz", "ounce", "ounces"):
        grams = qty * 28.3495
        return f"{round_metric(grams, 'g')}g"
    if unit_l in ("lb", "lbs", "pound", "pounds"):
        grams = qty * 453.592
        if grams >= 1000:
            return f"{round_metric(grams / 1000, 'kg')}kg"
        return f"{round_metric(grams, 'g')}g"
    if unit_l in ("cup", "cups"):
        # Cup is a volume unit, so weight depends on what's being measured.
        # Liquids convert cleanly to ml; dry/solid ingredients (herbs,
        # cheese, olives, flour, ...) get a rough weight-per-cup estimate
        # instead, since "355ml grated cheese" isn't a meaningful quantity
        # for a dry ingredient.
        if is_liquid_ingredient(context):
            ml = qty * 236.588
            if ml >= 1000:
                return f"{round_metric(ml / 1000, 'l')}l"
            return f"{round_metric(ml, 'ml')}ml"
        grams = estimate_cup_weight_grams(qty, context)
        if grams >= 1000:
            return f"{round_metric(grams / 1000, 'kg')}kg"
        return f"{round_metric(grams, 'g')}g"
    if unit_l in ("tbsp", "tbs", "tablespoon", "tablespoons"):
        # tsp/tbsp are genuinely volume units. For liquids (oil, milk,
        # stock, extract, ...) the honest conversion is ml. For dry
        # ingredients (spices, salt, sugar, ...) recipe charts commonly
        # approximate weight as roughly 1:1 with volume (~15g per tbsp) --
        # a reasonable approximation for many spices/salt/sugar, less so
        # for very light (herbs) or very dense (honey) ingredients.
        if is_liquid_ingredient(context):
            ml = qty * 14.7868
            return f"{round_metric(ml, 'ml')}ml"
        grams = qty * 14.7868
        return f"{round_metric(grams, 'g')}g"
    if unit_l in ("tsp", "teaspoon", "teaspoons"):
        if is_liquid_ingredient(context):
            ml = qty * 4.92892
            return f"{round_metric(ml, 'ml')}ml"
        grams = qty * 4.92892
        return f"{round_metric(grams, 'g')}g"
    if unit_l in ("in", "inch", "inches", '"'):
        cm = qty * 2.54
        return f"{round_metric(cm, 'cm')}cm"
    return original


def _local_liquid_context(match, radius=40):
    """
    Narrow the text used to decide whether a quantity describes a liquid
    (see is_liquid_ingredient) down to the clause the quantity actually
    appears in, rather than the entire string being processed.

    Using the whole string works fine when the string is one short
    ingredient line, but is wrong for a multi-sentence instructions
    paragraph that mentions several different ingredients -- e.g. "water"
    or "oil" mentioned anywhere else in a long paragraph would otherwise
    make an unrelated "cup of flour" earlier in that same paragraph get
    misread as a liquid too, converting it to ml instead of g.

    If the quantity sits inside a "(...)" aside -- the common case for the
    "(I used ...)" asides this script pulls ingredients from -- use just
    that parenthetical as context. Otherwise fall back to a modest
    character window around the match, which is still narrower than the
    whole string but wide enough to catch a liquid keyword sitting right
    next to a quantity that isn't inside parentheses at all.
    """
    s = match.string
    paren_start = s.rfind("(", 0, match.start())
    paren_end = s.find(")", match.end())
    if paren_start != -1 and paren_end != -1:
        return s[paren_start:paren_end + 1]
    return s[max(0, match.start() - radius):min(len(s), match.end() + radius)]


def _process_chain(match):
    """Handle a run of qty+unit tokens joined by '/' (e.g. '175 g/6 oz',
    '18 cm / 7 in'). Keep only the metric token(s); if the whole chain is
    imperial with no metric alternative given, convert the first token.

    Tokens are re-extracted from the matched text by finding each
    complete _CHAIN_TOKEN occurrence, not by splitting the text on '/' --
    a fractional quantity on one side of the chain (e.g. '3 1/2fl oz',
    from '100ml/3\u00bdfl oz' after fraction normalization) has its own
    internal '/' as part of the fraction itself, which a naive split
    can't distinguish from the '/' actually separating the two units,
    incorrectly cutting the fraction in half."""
    tokens = [t.group(0) for t in _CHAIN_TOKEN_RE.finditer(match.group(0))]
    metric_tokens, imperial_tokens = [], []
    for t in tokens:
        m = _TOKEN_SPLIT_RE.match(t)
        if not m:
            metric_tokens.append(t)  # unparsed segment: keep as-is, don't drop data
            continue
        qty_str, unit = m.group(1), m.group(2)
        if re.fullmatch(METRIC_UNITS, unit, re.IGNORECASE):
            metric_tokens.append(t)
        else:
            imperial_tokens.append((qty_str, unit))
    if metric_tokens:
        return "/".join(metric_tokens)
    if imperial_tokens:
        return convert_imperial_token(*imperial_tokens[0], context=_local_liquid_context(match))
    return match.group(0)


def _process_standalone(match):
    m = _STANDALONE_SPLIT_RE.match(match.group(0))
    if not m:
        return match.group(0)
    return convert_imperial_token(m.group(1), m.group(2), context=_local_liquid_context(match))


DEGREE_LOOKALIKE_RE = re.compile(r"\u02da(?=\s*[CF]\b)")

# Matches "N cup(s) (...)" where the parenthetical is a units-equivalent
# aside the recipe author already provided (e.g. "2 cups (16fl oz/450ml)",
# "1 cup (240ml)") -- as opposed to a parenthetical note unrelated to units
# (e.g. "2 cups (packed)"). Distinguishing the two is done in
# _process_cup_paren_equivalent by checking whether the captured text
# contains a recognizable metric unit at all.
CUP_PAREN_RE = re.compile(rf"(?:{QTY_TOKEN})\s?cups?\s*\(\s*([^()]*?)\s*\)", re.IGNORECASE)

# Matches a decimal number immediately followed by a metric unit (e.g. the
# "2.50" in "2.50g", the "3.00" in "3.00 kg"), used to trim needless
# trailing zeros -- a value like "2.50g" is no more precise than "2.5g",
# and "3.00kg" is just "3kg". Applies regardless of whether the decimal
# came from our own unit-conversion rounding (which already avoids this)
# or was simply stated that way in the source recipe's own text, which
# passes through untouched otherwise since there's no imperial unit
# needing conversion at all.
DECIMAL_BEFORE_METRIC_UNIT_RE = re.compile(
    rf"(\d+\.\d+)(?=\s?{METRIC_UNITS}(?![A-Za-z]))", re.IGNORECASE
)


def _trim_trailing_zeros(match):
    return match.group(1).rstrip("0").rstrip(".")


def _process_cup_paren_equivalent(match):
    """
    Collapses "N cup(s) (X unit/Y metric)" down to just the parenthetical's
    resolved metric value, e.g. "2 cups (16fl oz/450ml)" -> "450ml".

    Without this, the outer cup quantity gets converted independently via
    our rough, ingredient-keyword-based cup-to-weight estimate, while the
    parenthetical -- the recipe author's own precise, authoritative
    conversion -- is left untouched (or separately reprocessed) alongside
    it. That produces two different numbers for the same amount, e.g.
    "120g (225g) flour", where 120g is our generic guess and 225g is what
    the recipe itself actually says. The parenthetical wins.

    Left alone (returns the original text unchanged) when the
    parenthetical doesn't contain a metric unit at all -- e.g. "2 cups
    (packed)" or "2 cups (about half a jar)" -- since that's a genuine
    non-units note, not a units-equivalent aside, and the normal cup
    handling further down the pipeline should process the quantity as
    usual.
    """
    inner = match.group(1)
    if not re.search(rf"(?<![A-Za-z])(?:{METRIC_UNITS})(?![A-Za-z])", inner, re.IGNORECASE):
        return match.group(0)
    return CHAIN_RE.sub(_process_chain, inner)


def normalize_measurements(text):
    """Strip redundant imperial units when a metric equivalent is already
    given (e.g. '175 g/6 oz' -> '175 g'), and convert imperial-only
    quantities (weight, length, volume, oven temperature) to metric when no
    metric alternative is present at all."""
    if not text:
        return text

    # Some sources use "˚" (U+02DA, modifier letter ring above) instead of
    # the real degree sign "°" (U+00B0) -- a common copy-paste substitution
    # that looks identical at a glance. Normalize it first so it's both
    # typographically correct and recognized by the temperature patterns
    # below (which only match the real degree sign).
    text = DEGREE_LOOKALIKE_RE.sub("\u00b0", text)

    # Oven temperature: prefer/keep Celsius when both are given; convert a
    # lone Fahrenheit reading when no Celsius figure appears alongside it.
    text = CF_PAIR_RE.sub(lambda m: f"{m.group(1)}\u00b0C", text)
    text = FC_PAIR_RE.sub(lambda m: f"{m.group(2)}\u00b0C", text)

    # A Fahrenheit reading can also restate a Celsius value already given
    # earlier in the same text with unrelated words in between (e.g.
    # "160°C fan forced /320°F", common in Australian recipes) -- not
    # adjacent enough for CF_PAIR_RE/FC_PAIR_RE to recognize as a pair, but
    # still redundant. Converting it independently would otherwise leave a
    # duplicate "...160°C fan forced /160°C" with a dangling separator, so
    # drop the whole aside (separator, reading, and trailing paren) when
    # it's within rounding distance of a Celsius value already present.
    existing_celsius = [int(m) for m in re.findall(TEMP_C, text)]

    def _process_lone_f(m):
        celsius = round((int(m.group(1)) - 32) * 5 / 9 / 5) * 5
        if any(abs(celsius - c) <= 3 for c in existing_celsius):
            return ""
        return f"{celsius}\u00b0C"

    text = LONE_F_WITH_SEP_RE.sub(_process_lone_f, text)

    # "N cup(s) (units-equivalent)" -- collapse before the generic cup
    # handling below gets a chance to compute its own, separate estimate
    # for the same quantity.
    text = CUP_PAREN_RE.sub(_process_cup_paren_equivalent, text)

    # Weight/length/volume: metric/imperial pairs joined by '/', then any
    # remaining standalone imperial-only quantities.
    text = CHAIN_RE.sub(_process_chain, text)
    text = STANDALONE_RE.sub(_process_standalone, text)

    # Clean up any remaining over-precise metric decimals (our own
    # conversions above already avoid this, but a source recipe stating
    # an already-metric value like "2.50g" directly never passes through
    # round_metric at all, so it needs its own trimming pass here).
    text = DECIMAL_BEFORE_METRIC_UNIT_RE.sub(_trim_trailing_zeros, text)

    return text


LEADING_DESCRIPTOR_RE = re.compile(
    r"^([A-Za-z][A-Za-z\s]{2,40}?)\s+of\s+"
    r"(\d+(?:\s\d+/\d+|/\d+)?)\s+"
    r"(.+)$"
)


TRAILING_RECIPE_WORD_RE = re.compile(r"[\s\-|:]*recipe\.?\s*$", re.IGNORECASE)


def strip_redundant_title_suffix(name):
    """
    Many recipe sites' titles end in a redundant "Recipe" (e.g. "Seared
    Barramundi with Corn Salad Recipe"), inherited from an SEO-oriented
    <title> tag or site-wide template. That's fine on the original website,
    but redundant noise once the recipe lives in a dedicated recipe manager
    (e.g. Nextcloud Cookbook) that already knows it's a recipe. Strips a
    trailing "Recipe" word (with any immediately preceding punctuation/
    whitespace), case-insensitively. Leaves the title alone if that would
    remove the whole thing (e.g. a title that's just "Recipe" outright,
    however unlikely) -- something is better than nothing.
    """
    if not name:
        return name
    stripped = TRAILING_RECIPE_WORD_RE.sub("", name).strip()
    return stripped if stripped else name


def reorder_leading_descriptor(text):
    """
    Some ingredient phrasing puts the quantity in the middle of the
    sentence rather than at the start (e.g. 'Grated rind of 1 lemon',
    'Juice of 2 limes'). Tools that need a leading 'amount unit ingredient'
    format -- e.g. Nextcloud Cookbook's serving-size recalculation --
    can't parse those and will flag a syntax error. This moves the
    quantity to the front: 'Grated rind of 1 lemon' -> '1 lemon, grated
    rind'.
    """
    if not text:
        return text
    m = LEADING_DESCRIPTOR_RE.match(text.strip())
    if not m:
        return text
    descriptor, qty, rest = m.group(1).strip(), m.group(2).strip(), m.group(3).strip()
    return f"{qty} {rest}, {descriptor.lower()}"


def merge_keywords(*sources):
    """
    Merge multiple keyword sources into one deduplicated, order-preserving,
    comma-separated keywords string -- schema.org's Recipe.keywords is
    conventionally a single comma-delimited string, and this keeps that
    format regardless of how many separate sources feed into it (a site's
    own keywords list, a "Difficulty: Easy" label, a category tag, ...) or
    what shape each one comes in.

    Each source may be None (skipped), a plain string, a comma-separated
    string (split into individual keywords), or a list/tuple of strings.
    Sources are processed in the order given, so if the same keyword shows
    up from two different sources, only its first occurrence is kept.
    Returns None if no source yields anything.

    Used anywhere keywords get assembled, so adding a new keyword source
    later is just one more argument here rather than a new spot that
    silently overwrites whatever a previous source already set.
    """
    seen = set()
    merged = []
    for source in sources:
        if not source:
            continue
        if isinstance(source, str):
            parts = [p.strip() for p in source.split(",")]
        elif isinstance(source, (list, tuple)):
            parts = [str(p).strip() for p in source]
        else:
            continue
        for part in parts:
            if not part:
                continue
            key = part.lower()
            if key in seen:
                continue
            seen.add(key)
            merged.append(part)
    return ", ".join(merged) if merged else None


def normalize_ingredient_phrasing(ingredients):
    """Apply reorder_leading_descriptor to a recipeIngredient list."""
    if not isinstance(ingredients, list):
        return ingredients
    return [reorder_leading_descriptor(i) if isinstance(i, str) else i for i in ingredients]


NUMBER_START_RE = re.compile(r"^\s*\d")


NO_QUANTITY_MARKERS = (
    "to taste", "as needed", "for frying", "for serving", "optional",
    "to season", "for seasoning",
)

# Matches "salt" or "pepper" as whole words (so "peppermint"/"saltine"
# aren't caught, but "sea salt", "black pepper", "salt and pepper" are).
# Used by ensure_leading_quantity to give these a more sensible default
# quantity than a bare "1" when no amount is stated at all.
SALT_OR_PEPPER_RE = re.compile(r"\b(?:salt|pepper)\b", re.IGNORECASE)


def ensure_leading_quantity(ingredients):
    """
    Tools that recalculate ingredient amounts by serving size (e.g.
    Nextcloud Cookbook) require every ingredient line to start with a
    parseable amount. Lines with no quantity at all -- e.g. 'Fresh
    parsley, chopped' or 'Lemon juice' -- get a nominal '1 ' prefix so
    they parse as 'amount ingredient', even though '1' isn't a precise
    measurement for something like a garnish. Group-heading lines (e.g.
    'To serve:', marked with a trailing colon) are left alone since
    they aren't ingredients at all.

    Lines that already say the quantity is intentionally open-ended (e.g.
    'canola oil, as needed') are also left alone: unlike a garnish, where
    '1' is at least a plausible whole-item guess, prefixing '1' onto '...,
    as needed' is self-contradictory rather than merely imprecise.

    An unquantified salt/pepper line (e.g. 'Salt and pepper' with no
    amount given at all) gets '1 pinch of' instead of the generic '1' --
    a bare '1' reads as one whole unit of salt, which is a much odder
    default guess than a pinch is for a seasoning that's rarely measured
    as a discrete count in the first place. Unlike the generic '1'
    prefix, this applies even to lines that also carry an open-ended
    marker (e.g. 'Salt and pepper, to taste' -> '1 pinch of Salt and
    pepper, to taste'): a pinch is already an informal, approximate
    measure, so pairing it with "to taste" isn't self-contradictory the
    way "1 canola oil, as needed" would be.
    """
    if not isinstance(ingredients, list):
        return ingredients
    result = []
    for item in ingredients:
        if isinstance(item, str):
            stripped = item.strip()
            already_open_ended = any(marker in stripped.lower() for marker in NO_QUANTITY_MARKERS)
            if stripped and not NUMBER_START_RE.match(stripped) and not stripped.endswith(":"):
                if SALT_OR_PEPPER_RE.search(stripped):
                    # A pinch is already an informal, approximate measure,
                    # so "1 pinch of salt, to taste" or "..., for
                    # seasoning" isn't self-contradictory the way "1
                    # canola oil, as needed" would be -- so this applies
                    # even when an open-ended marker is also present,
                    # unlike the generic '1' prefix below.
                    if "pinch" in stripped.lower():
                        # Already says "pinch" itself (e.g. "pinch of
                        # salt") -- just needs the leading number, not a
                        # second, redundant "pinch of" on top.
                        item = f"1 {stripped}"
                    else:
                        item = f"1 pinch of {stripped}"
                elif not already_open_ended:
                    item = f"1 {stripped}"
        result.append(item)
    return result


def normalize_measurements_deep(obj):
    """Recursively apply normalize_measurements to every string value in a
    JSON-like structure (dict/list/str), leaving other types untouched."""
    if isinstance(obj, str):
        return normalize_measurements(obj)
    if isinstance(obj, dict):
        return {k: normalize_measurements_deep(v) for k, v in obj.items()}
    if isinstance(obj, list):
        return [normalize_measurements_deep(v) for v in obj]
    return obj


def clean_step_text(text):
    """Collapse embedded newline-plus-whitespace runs (e.g. from multi-line
    source markup) into a single space, so HowToStep text reads as one
    continuous line."""
    if not text:
        return text
    text = re.sub(r"\s*\n\s*", " ", text)
    text = re.sub(r" {2,}", " ", text)
    return text.strip()


def safe(fn, default=None):
    """Call a scraper method, swallowing errors for fields a given site doesn't provide."""
    try:
        result = fn()
        return result if result not in ("", [], {}) else default
    except (RecipeScrapersExceptions, AttributeError, NotImplementedError, Exception):
        return default


def build_recipe_jsonld(scraper, canonical_url=None):
    """Map a recipe_scrapers scraper instance onto a schema.org Recipe dict."""

    instructions_list = safe(scraper.instructions_list, [])
    if instructions_list:
        instructions = [
            {"@type": "HowToStep", "text": clean_step_text(step)} for step in instructions_list
        ]
    else:
        raw = safe(scraper.instructions, "")
        instructions = [
            {"@type": "HowToStep", "text": clean_step_text(line)}
            for line in raw.split("\n")
            if line.strip()
        ]

    images = safe(scraper.image)
    image_list = images if isinstance(images, list) else ([images] if images else [])

    author = safe(scraper.author)
    author_obj = {"@type": "Person", "name": author} if author else None

    ratings_value = safe(scraper.ratings)
    ratings_count = safe(scraper.ratings_count)
    aggregate_rating = None
    if ratings_value:
        aggregate_rating = {
            "@type": "AggregateRating",
            "ratingValue": ratings_value,
        }
        if ratings_count:
            aggregate_rating["ratingCount"] = ratings_count

    nutrients = safe(scraper.nutrients)
    nutrition = None
    if nutrients:
        nutrition = {"@type": "NutritionInformation", **nutrients}

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": safe(scraper.title),
        "description": safe(scraper.description),
        "author": author_obj,
        "image": image_list,
        "recipeYield": safe(scraper.yields),
        "prepTime": iso8601_duration(safe(scraper.prep_time)),
        "cookTime": iso8601_duration(safe(scraper.cook_time)),
        "totalTime": iso8601_duration(safe(scraper.total_time)),
        "recipeCategory": safe(scraper.category),
        "recipeCuisine": safe(scraper.cuisine),
        "keywords": merge_keywords(safe(scraper.keywords)),
        "recipeIngredient": safe(scraper.ingredients, []),
        "recipeInstructions": instructions,
        "aggregateRating": aggregate_rating,
        "nutrition": nutrition,
        "url": canonical_url or safe(scraper.canonical_url),
    }

    # Drop keys that are None / empty so the JSON-LD stays clean
    return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}


def detect_canonical_url(html):
    """
    Best-effort detection of a page's own canonical URL from its HTML.
    Used when the user runs --file without also passing --url: without a
    base URL, relative image/link URLs found while scraping (e.g.
    "/content/dam/.../photo.jpg") can't be resolved to absolute ones and
    end up unusable in the output. Checks <link rel="canonical"> first,
    then an og:url meta tag (both are standard, widely-supported ways for
    a page to declare its own URL). Returns None if neither is present.
    """
    soup = BeautifulSoup(html, "html.parser")
    canonical = soup.find("link", attrs={"rel": "canonical"})
    if canonical and canonical.get("href"):
        return canonical["href"].strip()
    og_url = soup.find("meta", attrs={"property": "og:url"})
    if og_url and og_url.get("content"):
        return og_url["content"].strip()
    return None


def fetch_html(url):
    request = Request(url, headers={"User-Agent": USER_AGENT})
    return urlopen(request).read().decode("utf-8", errors="replace")


def split_ingredient_block(text):
    """Split a chunk of run-together ingredient text into individual ingredients."""
    # First split on actual line breaks (from <br> tags or literal newlines).
    lines = [l.strip(" -\u2022\t") for l in text.split("\n") if l.strip()]
    if not lines:
        return []

    # Each resulting line may itself contain several ingredients crammed
    # together with no delimiter at all (common when a site's markup only
    # has a <br> here and there) -- split those further on quantity+unit
    # boundaries.
    result = []
    for line in lines:
        pieces = [p.strip(" ,") for p in UNIT_PATTERN.split(line) if p.strip(" ,")]
        result.extend(pieces if len(pieces) > 1 else [line])
    return result


def find_best_image(soup, url=None):
    """
    Best-effort search for a representative recipe photo, in priority order:
      1. og:image / twitter:image meta tags (most reliable when present)
      2. <link rel="image_src">
      3. The largest plausible content <img> on the page, skipping obvious
         chrome (logos, icons, ads, spacers, nav/social/share graphics)
    Returns an absolute URL, or None if nothing suitable is found.
    """
    def absolutize(src):
        return urljoin(url, src) if url else src

    for prop in ("og:image", "og:image:secure_url", "twitter:image", "twitter:image:src"):
        tag = soup.find("meta", attrs={"property": prop}) or soup.find("meta", attrs={"name": prop})
        if tag and tag.get("content"):
            return absolutize(tag["content"].strip())

    link_tag = soup.find("link", attrs={"rel": "image_src"})
    if link_tag and link_tag.get("href"):
        return absolutize(link_tag["href"].strip())

    candidates = []
    for img in soup.find_all("img"):
        src = img.get("src") or img.get("data-src") or img.get("data-lazy-src")
        if not src:
            continue
        src_clean = src.strip()
        if not src_clean.lower().split("?")[0].endswith(IMAGE_EXTENSIONS):
            continue
        if IMAGE_SKIP_PATTERN.search(src_clean):
            continue

        # Use width/height attributes as a rough size signal when available;
        # otherwise fall back to a neutral score so the image isn't excluded.
        try:
            width = int(img.get("width", 0))
            height = int(img.get("height", 0))
        except ValueError:
            width = height = 0
        area = width * height

        # Tiny declared dimensions (icons, tracking pixels) are disqualifying.
        if width and width < 100:
            continue
        if height and height < 100:
            continue

        alt = (img.get("alt") or "").lower()
        title_bonus = 1 if any(w in alt for w in ("recipe", "cake", "dish", "food")) else 0

        candidates.append((area, title_bonus, absolutize(src_clean)))

    if not candidates:
        return None

    # Prefer images explicitly tagged as food-related, then by declared area.
    candidates.sort(key=lambda c: (c[1], c[0]), reverse=True)
    return candidates[0][2]


def find_recipe_node(obj, _depth=0):
    """
    Recursively search a parsed JSON blob (e.g. a Next.js __NEXT_DATA__
    payload) for a dict that looks like a structured recipe object. Many
    modern news/publisher sites render recipes client-side from an embedded
    JSON app-state blob rather than emitting schema.org markup, so this is
    tried as a middle step between recipe-scrapers and the raw-HTML
    heuristic parser.
    """
    if _depth > 12:
        return None
    if isinstance(obj, dict):
        if "recipeIngredientsPrepared" in obj or "recipeInstructionsPrepared" in obj:
            return obj
        for v in obj.values():
            found = find_recipe_node(v, _depth + 1)
            if found:
                return found
    elif isinstance(obj, list):
        for item in obj:
            found = find_recipe_node(item, _depth + 1)
            if found:
                return found
    return None


def render_descriptor_text(node):
    """Flatten a rich-text 'descriptor' node tree (CoreMedia/React-style) into plain text."""
    if node is None:
        return ""
    if isinstance(node, list):
        return "".join(render_descriptor_text(n) for n in node)
    if not isinstance(node, dict):
        return ""
    if node.get("type") == "text":
        return node.get("content", "")
    if node.get("type") == "embed":
        return ""  # skip images/embeds inside rich text
    return "".join(render_descriptor_text(c) for c in node.get("children") or [])


def extract_list_items(node, tag_names=("li",)):
    """Find all <li>-equivalent nodes within a descriptor tree, returning their flattened text."""
    items = []

    def walk(n):
        if isinstance(n, list):
            for x in n:
                walk(x)
            return
        if not isinstance(n, dict):
            return
        if n.get("key") in tag_names:
            text = render_descriptor_text(n).strip()
            if text:
                items.append(text)
            return  # don't descend further to avoid duplicate/nested text
        for c in n.get("children") or []:
            walk(c)

    walk(node)
    return items


def extract_paragraphs(node):
    """Fallback: flatten <p>/standfirst text blocks in a descriptor tree, one string per block."""
    paras = []

    def walk(n):
        if isinstance(n, list):
            for x in n:
                walk(x)
            return
        if not isinstance(n, dict):
            return
        if n.get("key") in ("p", "@@standfirst"):
            text = render_descriptor_text(n).strip()
            if text:
                paras.append(text)
            return
        for c in n.get("children") or []:
            walk(c)

    walk(node)
    return paras


def pick_best_media_image(featured_media):
    """Given a featuredMedia-style list with nested picture/cropInfo data, pick one representative image URL."""
    if not featured_media:
        return None
    for media in featured_media:
        picture = media.get("picture") or media
        for group in picture.get("cropInfo") or []:
            if group.get("key") == "large":
                ratios = group.get("value") or []
                for want in ("16x9", "4x3", "3x2", "1x1"):
                    for r in ratios:
                        if r.get("ratio") == want and r.get("url"):
                            return r["url"]
                if ratios and ratios[0].get("url"):
                    return ratios[0]["url"]
    return None


def build_recipe_from_app_state(node, url=None):
    """Map a recipe-like dict found inside an embedded JSON app-state blob onto schema.org Recipe."""
    name = node.get("name")
    canonical = node.get("canonicalURL") or url

    author = None
    authors = (node.get("contributors") or {}).get("author") or []
    if isinstance(authors, list) and authors:
        author = authors[0].get("name")

    ingredients = []
    for group in (node.get("recipeIngredientsPrepared") or {}).get("ingredients") or []:
        heading = group.get("heading")
        group_ingredients = group.get("ingredients") or []
        if heading:
            # schema.org's Recipe type has no real construct for grouped
            # ingredients (e.g. "For the topping" / "To serve"), and tools
            # like Nextcloud Cookbook just store a flat list of strings --
            # so a bare heading line ends up rendered as if it were an
            # actual (unquantified) ingredient. Fold the heading into each
            # ingredient in that group instead of emitting it as its own
            # fake ingredient line.
            heading_clean = heading.rstrip(":").strip()
            group_ingredients = [f"{ing} ({heading_clean.lower()})" for ing in group_ingredients]
        ingredients.extend(group_ingredients)

    instructions = []
    instr_node = ((node.get("recipeInstructionsPrepared") or {}).get("instructions") or {}).get("descriptor")
    if instr_node:
        steps = extract_list_items(instr_node, tag_names=("li",))
        if not steps:
            steps = extract_paragraphs(instr_node)
        instructions = [{"@type": "HowToStep", "text": clean_step_text(s)} for s in steps]

    description = None
    text_node = (node.get("text") or {}).get("descriptor")
    if text_node:
        paras = extract_paragraphs(text_node)
        if paras:
            description = " ".join(paras[:2])  # keep it to the intro, not the full body

    image_url = pick_best_media_image(node.get("featuredMedia"))

    keywords = merge_keywords(node.get("keywords"))

    category = node.get("recipeCategory")
    if isinstance(category, list):
        category = ", ".join(category)

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": name,
        "description": description,
        "author": {"@type": "Person", "name": author} if author else None,
        "image": [image_url] if image_url else None,
        "recipeYield": node.get("recipeYield"),
        "prepTime": iso8601_duration(node.get("preparationTime")),
        "cookTime": iso8601_duration(node.get("cookingTime")),
        "totalTime": iso8601_duration(node.get("totalTime")),
        "recipeCategory": category,
        "keywords": keywords,
        "recipeIngredient": ingredients,
        "recipeInstructions": instructions,
        "url": canonical,
    }
    return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}


def try_load_existing_jsonld(file_content):
    """
    If a --file argument points at a file this script already produced,
    parse and return that JSON directly instead of re-scraping it as if
    it were a fresh recipe page. Re-scraping it would lose information --
    notably, recipe-scrapers' generic parser can technically read our own
    embedded JSON-LD back in, but its canonical_url() only ever returns
    whatever --url was passed (or the 'https://example.com' placeholder
    if none was), throwing away the real URL that's already sitting right
    there in the file.

    Detected via a distinctive HTML comment this script always writes
    just before its own <script type="application/ld+json"> output (see
    main()) -- not just the presence of a Recipe-typed JSON-LD script tag
    on its own. That looser check used to be the whole test here, but it's
    a serious false-positive magnet: a normal, legitimately-marked-up
    recipe source page has exactly the same shape (a <script
    type="application/ld+json"> containing a top-level Recipe object),
    so it kept mistaking real, unprocessed source pages for this script's
    own prior output and silently reusing whatever (possibly incomplete
    or nonstandard) JSON-LD the source site itself shipped, skipping this
    script's entire scraping/normalization pipeline in the process.

    Returns None if this doesn't look like one of our own output files.
    """
    if "<!-- generated by recipe_to_jsonld.py -->" not in file_content:
        return None
    m = re.search(
        r'<script\s+type=["\']application/ld\+json["\']\s*>(.*?)</script>',
        file_content, re.DOTALL | re.IGNORECASE,
    )
    if not m:
        return None
    try:
        data = json.loads(m.group(1))
    except json.JSONDecodeError:
        return None
    if isinstance(data, dict) and data.get("@type") == "Recipe":
        return data
    return None


def jsonld_description_scrape(html, url=None):
    """
    Some lightweight 'article' recipe pages don't use a proper schema.org
    Recipe type at all -- their only JSON-LD is a WebPage/Article block,
    and the entire recipe (ingredients + method) is embedded as one long
    plain-text string in the 'description' field, with 'Ingredients' and
    'Method' as plain-text section markers (e.g. "...Ingredients\\n1 cup
    cream\\n1 tsp vinegar\\nMethod\\nPour the cream into a jar..."). This
    looks for that specific pattern and extracts a usable recipe from it.
    Returns None if no such pattern is found.
    """
    soup = BeautifulSoup(html, "html.parser")
    for tag in soup.find_all("script", attrs={"type": "application/ld+json"}):
        if not tag.string:
            continue
        try:
            data = json.loads(tag.string)
        except json.JSONDecodeError:
            continue
        candidates = data if isinstance(data, list) else [data]
        for obj in candidates:
            if not isinstance(obj, dict) or obj.get("@type") not in (
                "WebPage", "Article", "NewsArticle", "BlogPosting"
            ):
                continue
            description = obj.get("description")
            if not isinstance(description, str):
                continue
            m = re.search(
                r"Ingredients\s*\n+(.*?)\n+Method\s*\n+(.*)$",
                description, re.S | re.IGNORECASE,
            )
            if not m:
                continue

            ingredients = [line.strip() for line in m.group(1).split("\n") if line.strip()]

            steps = []
            for line in m.group(2).split("\n"):
                line = line.strip()
                if not line:
                    continue
                steps.extend(
                    s.strip() for s in re.split(r"(?<=[.!?])\s+(?=[A-Z])", line) if s.strip()
                )
            instructions = [{"@type": "HowToStep", "text": clean_step_text(s)} for s in steps]

            intro = description.split("Ingredients")[0].strip()
            intro_first_para = intro.split("\n")[0].strip() if intro else None

            image_field = obj.get("image")
            image_url = None
            if isinstance(image_field, list) and image_field:
                first_img = image_field[0]
                image_url = first_img.get("url") if isinstance(first_img, dict) else first_img
            elif isinstance(image_field, dict):
                image_url = image_field.get("url")
            elif isinstance(image_field, str):
                image_url = image_field

            author_field = obj.get("author")
            author = author_field.get("name") if isinstance(author_field, dict) else None

            recipe = {
                "@context": "https://schema.org",
                "@type": "Recipe",
                "name": obj.get("name"),
                "description": intro_first_para,
                "author": {"@type": "Person", "name": author} if author else None,
                "image": [image_url] if image_url else None,
                "recipeIngredient": ingredients,
                "recipeInstructions": instructions,
                "url": url or obj.get("url"),
            }
            result = {k: v for k, v in recipe.items() if v not in (None, "", [], {})}
            if result.get("recipeIngredient") or result.get("recipeInstructions"):
                return result
    return None


def app_state_scrape(html, url=None):
    """
    Look for an embedded JSON app-state blob (e.g. Next.js's
    <script id="__NEXT_DATA__" type="application/json">) containing a
    structured recipe object, and convert it if found. Returns None if no
    such blob/recipe is present so callers can fall through to the next
    strategy.
    """
    soup = BeautifulSoup(html, "html.parser")

    scripts = []
    next_data = soup.find("script", id="__NEXT_DATA__")
    if next_data and next_data.string:
        scripts.append(next_data.string)
    for tag in soup.find_all("script", attrs={"type": "application/json"}):
        if tag is next_data or not tag.string:
            continue
        scripts.append(tag.string)

    for raw in scripts:
        try:
            data = json.loads(raw)
        except (json.JSONDecodeError, TypeError):
            continue
        recipe_node = find_recipe_node(data)
        if recipe_node:
            result = build_recipe_from_app_state(recipe_node, url=url)
            if result.get("recipeIngredient") or result.get("recipeInstructions"):
                return result
    return None


# Filler words stripped when building a short ingredient descriptor out of
# the prose immediately preceding an "(I used ...)" aside -- see
# extract_iused_ingredients() below. Includes ordinary stopwords plus a few
# generic kitchen-prep nouns (bowl, station, pan, ...) that tend to sit right
# next to the actual ingredient name but aren't themselves ingredients.
IUSED_STOPWORDS = {
    "a", "an", "the", "of", "with", "each", "this", "that", "some", "and",
    "my", "for", "in", "on", "to", "into", "was", "used", "bowl", "station",
    "set", "up", "pan", "plate", "dish", "own",
}
IUSED_PATTERN = re.compile(r"([A-Za-z0-9,\-\s]{0,60}?)\(I used ([^)]+)\)", re.IGNORECASE)
PLUS_SPLIT_PATTERN = re.compile(r"\s+plus\s+", re.IGNORECASE)


# Recognizable ingredient nouns, reused from the liquid/dry-weight keyword
# tables already in this script, plus a few other very common basics. Used
# by extract_iused_ingredients() to spot the actual ingredient name even
# when it's buried inside a longer descriptive clause.
KNOWN_INGREDIENT_NOUNS = (
    set(LIQUID_KEYWORDS)
    | {kw for group, _ in CUP_WEIGHT_TABLE for kw in group}
    | {"salt", "pepper", "egg", "eggs", "cheese", "sugar"}
)


# Rough, generic placeholder amounts for ingredients that typically show up
# as a bare type/brand with no quantity at all (e.g. "I used canola" for
# frying oil). These are NOT derived from the specific recipe being
# parsed -- there's no way to know the pan size or oil depth this article
# actually used -- they're just a plausible generic amount for that kind of
# ingredient. Rendered as a plain leading number (not "~250ml" or similar)
# so it still parses as a valid quantity for tools like Nextcloud Cookbook
# that require one for serving-size scaling; the trailing "(amount not
# stated in source)" note is what flags it as a guess rather than something
# extracted from the text. Deliberately small and conservative: expand only
# for ingredient types where a genuinely typical amount exists (e.g.
# shallow-frying oil in a pan), not for anything where the "right" amount
# varies too much to guess at all (there's no sensible generic default for
# "cheese" or "nuts").
DEFAULT_QUANTITY_GUESSES = (
    (("oil",), "250ml"),  # roughly enough to shallow-fry in a pan/skillet
)


def guess_default_quantity(line):
    """Look up a rough placeholder amount for a type-only ingredient line
    (e.g. 'canola oil'), based on keywords in the line. Returns None if no
    sensible generic default exists for this kind of ingredient."""
    lower = line.lower()
    for keywords, amount in DEFAULT_QUANTITY_GUESSES:
        if any(kw in lower for kw in keywords):
            return amount
    return None


def extract_iused_ingredients(text):
    """
    Best-effort extraction of ingredient quantities from personal-blog-style
    "how I made it" prose, which -- lacking any real ingredients list --
    often still names the exact amount used in a parenthetical aside right
    after the ingredient, e.g. "a bowl of flour (I used 3/4 cup)" or "an oil
    with a high smoking point (I used canola)".

    For each match, builds one ingredient line by combining the "(I used
    ...)" content with a short cleaned-up descriptor taken from the words
    immediately before it -- unless that content already names the
    ingredient itself (e.g. "beaten eggs (I used 2 eggs plus 2 tablespoons
    of water)"), in which case the descriptor would just be redundant noise
    and is dropped.

    This is inherently a rough approximation of a real ingredients list, so
    callers should tell the user to review/complete it -- prose rarely
    marks every ingredient this way (a plain "eggplant" or "salt" mentioned
    without an "(I used ...)" aside won't be picked up at all).
    """

    def clean_descriptor(text_before, max_words=6, max_descriptor_words=2):
        words = [w.strip(",.") for w in text_before.strip().split()[-max_words:]]
        words = [w for w in words if w.lower() not in IUSED_STOPWORDS]
        if not words:
            return ""
        # If a recognizable ingredient noun turns up among the leftover
        # words, that's a much stronger signal of the actual ingredient
        # name than raw position is -- e.g. "oil" inside "oil high smoking
        # point" (from "...oil with a high smoking point (I used
        # canola)"). Prefer that single word over the whole descriptive
        # clause it's embedded in.
        for w in words:
            key = w.lower().rstrip("s")
            if key in KNOWN_INGREDIENT_NOUNS or w.lower() in KNOWN_INGREDIENT_NOUNS:
                return w.lower()
        # Otherwise, a short leftover span (after stripping filler) is
        # usually just the ingredient name itself (e.g. "flour", "bread
        # crumbs"). A longer one is more likely a descriptive clause with
        # no recognizable noun in it -- gluing that onto the amount risks
        # nonsense like "canola oil high smoking point", so discard it
        # rather than guess wrong. The caller falls back to the
        # parenthetical alone, short of a name but at least not misleading.
        if len(words) > max_descriptor_words:
            return ""
        return " ".join(words).strip(" ,.")

    def word_set(s):
        return {re.sub(r"[^a-z]", "", w.lower()).rstrip("s") for w in s.split()}

    ingredients = []
    for m in IUSED_PATTERN.finditer(text or ""):
        descriptor = clean_descriptor(m.group(1))
        # A parenthetical can itself list more than one thing joined by
        # "plus" (e.g. "I used 2 eggs plus 2 tablespoons of water" for an
        # egg wash) -- each part is a distinct ingredient and should be its
        # own line, not one run-on ingredient. Only the first part can
        # plausibly need the preceding descriptor; later parts already name
        # themselves (e.g. "water" doesn't need "beaten eggs" tacked on).
        parts = [p.strip() for p in PLUS_SPLIT_PATTERN.split(m.group(2).strip()) if p.strip()]
        for i, part in enumerate(parts):
            if i == 0 and descriptor and not (word_set(descriptor) & word_set(part)):
                line = f"{part} {descriptor}".strip()
            else:
                line = part
            if not line:
                continue
            # Some asides name a type/brand rather than an amount at all
            # (e.g. "an oil with a high smoking point (I used canola)" only
            # tells us it was canola, never how much). For those, use a
            # clearly-labeled rough guess if one exists for this kind of
            # ingredient (see DEFAULT_QUANTITY_GUESSES), otherwise just
            # flag it "as needed" -- either way, never let it fall through
            # to ensure_leading_quantity()'s fallback, which would
            # otherwise fabricate a meaningless bare "1" in front of it.
            if not NUMBER_START_RE.match(part):
                guess = guess_default_quantity(line)
                if guess:
                    line = f"{guess} {line}, as needed (amount not stated in source)"
                else:
                    line = f"{line}, as needed"
            ingredients.append(line)
    return ingredients


# A leading quantity + common recipe unit, used by br_line_scrape() to spot
# ingredient-shaped lines by content rather than by any markup or heading --
# useful on pages where quantities are just bare text between <br> tags with
# no real list structure to key off (common on low-effort/AI-templated
# content, but not exclusive to it).
INGREDIENT_LINE_RE = re.compile(
    r"^\s*(?:\d+\s\d+/\d+|\d+/\d+|\d+(?:\.\d+)?)\s*"
    r"(?:g|kg|ml|l|cups?|tbsp|tbs|tablespoons?|tsp|teaspoons?|oz|lbs?|pinch(?:es)?|cloves?|slices?)\b",
    re.IGNORECASE,
)

# Matches a leading "Step N:"/"Step N -"/"Step N." label on a heading or
# list item, so it can be stripped once the step's position is already
# conveyed structurally (as a HowToStep in a list/sequence) and doesn't
# need repeating inside the text itself.
STEP_LABEL_RE = re.compile(r"^\s*step\s*\d+\s*[:.\-]?\s*", re.IGNORECASE)

# Matches a leading "Makes"/"Serves"/"Yield(s)" label on a yield
# statement, stripped so the value starts with a number (e.g. "Makes 10
# scones" -> "10 scones") -- tools like Nextcloud Cookbook expect a
# leading number here just as they do for ingredient quantities.
YIELD_LABEL_RE = re.compile(r"^\s*(?:makes|serves|yields?)\s*(?:of)?\s*:?\s*", re.IGNORECASE)

# Splits a yield statement from an ingredient that got run onto the same
# line with no separator in the source (a real markup quirk seen in the
# wild, not a delimiter we should expect reliably) -- e.g. "Makes 10
# scones 250 g (1 cup / 8 oz) smashed pumpkin" is two separate things, not
# one long yield statement. Finds the first point where a NEW quantity+
# unit begins after the leading "Makes/Serves/Yields" word and splits
# there; group(1) is the yield phrase, group(2) is everything from the
# new ingredient onward.
YIELD_INGREDIENT_SPLIT_RE = re.compile(
    r"^((?:makes|serves|yields?)\b.*?)\s+"
    r"(\d+(?:\.\d+)?\s*(?:g|kg|ml|l|cups?|tbsp|tsp|teaspoons?|tablespoons?|oz|lbs?)\b.*)$",
    re.IGNORECASE,
)


def clean_recipe_yield(text):
    """Strips a leading 'Makes'/'Serves'/'Yield(s)' label so the result
    starts with a number. Returns the original text unchanged if nothing
    would be left after stripping (shouldn't normally happen, but better
    than returning an empty yield)."""
    if not text:
        return text
    stripped = YIELD_LABEL_RE.sub("", text).strip()
    return stripped or text


def _walk_line_stream(node, stop_tags=("h1", "h2", "h3", "h4")):
    """
    Walk `node`'s content in document order, yielding a flat stream of
    ('text', str), ('br',), and ('li', tag) events -- recursing into plain
    inline wrapper tags (em, strong, span, ...) but treating <li> as an
    atomic unit (using its own get_text() rather than recursing into it)
    and stopping entirely at the first heading tag.

    This exists for pages where broken/unclosed markup (e.g. an <em> that
    never closes) has nested real content -- a step list, even a
    heading -- inside what looks like one long paragraph. A plain
    .find_all() wouldn't reflect the document's real reading order in that
    case; walking .children recursively, node by node, does, and lets the
    caller decide what a heading boundary should mean without silently
    wandering into an unrelated later section.

    Yields a final ('stop',) event instead of returning if a stop_tag is
    hit, so the caller knows to stop collecting rather than assuming the
    node's content was exhausted normally.
    """
    for child in node.children:
        name = getattr(child, "name", None)
        if name in stop_tags:
            yield ("stop",)
            return
        if name == "br":
            yield ("br",)
            # A well-formed <br> is a void element with no children at
            # all, so this is normally a no-op. But some editors emit a
            # malformed "<br>...</br>" pair (an illegal closing tag) --
            # our parser then treats everything up to that stray </br> as
            # the <br>'s own children rather than void/self-closing, which
            # would otherwise make all of that content vanish silently
            # instead of being walked at all.
            for ev in _walk_line_stream(child, stop_tags):
                if ev[0] == "stop":
                    yield ev
                    return
                yield ev
            continue
        if name == "li":
            yield ("li", child)
            continue
        if name is None:
            yield ("text", str(child))
            continue
        for ev in _walk_line_stream(child, stop_tags):
            if ev[0] == "stop":
                yield ev
                return
            yield ev


def _wprm_field_value(container, field, label_suffix="-name"):
    """
    WP Recipe Maker's convention: a field's value lives on an element whose
    class is exactly 'wprm-recipe-{field}' (possibly alongside other
    classes, e.g. a per-recipe-ID variant), while the human-readable label
    ("Author", "Servings", ...) lives on a separate element with an extra
    '-name' suffix. Returns the value element's text, or None if absent.
    """
    el = container.find(class_=lambda c, field=field: c == f"wprm-recipe-{field}")
    return el.get_text(" ", strip=True) if el else None


def _wprm_minutes(soup, field):
    """
    Sums a WP Recipe Maker time field into total minutes. WPRM splits a
    duration across separate hour/minute spans that share the same base
    class (e.g. 'wprm-recipe-total_time' appears on both an -hours span
    valued 6 and a -minutes span valued 15, for a 6h15m total) rather than
    one combined field, so this finds every matching span and sums them,
    treating any span whose class list also includes an '-hours' suffix as
    hours and everything else as minutes. Returns None if the field isn't
    present on the page at all.
    """
    total_minutes = 0.0
    found = False
    for span in soup.find_all(class_=lambda c, field=field: c == f"wprm-recipe-{field}"):
        text = span.get_text(strip=True)
        if not re.match(r"^\d+(\.\d+)?$", text):
            continue
        value = float(text)
        found = True
        classes = span.get("class") or []
        if f"wprm-recipe-{field}-hours" in classes:
            total_minutes += value * 60
        else:
            total_minutes += value
    return int(round(total_minutes)) if found else None


def _wprm_best_image(soup):
    """Picks the largest available image from a WPRM recipe-image
    container's srcset, falling back to its plain src. Returns None if no
    image is present at all."""
    container = soup.find(class_="wprm-recipe-image-container")
    if not container:
        return None
    img = container.find("img")
    if not img:
        return None
    srcset = img.get("srcset")
    if srcset:
        candidates = []
        for part in srcset.split(","):
            part = part.strip().split()
            if len(part) == 2 and part[1].endswith("w"):
                try:
                    candidates.append((int(part[1][:-1]), part[0]))
                except ValueError:
                    continue
        if candidates:
            return max(candidates, key=lambda c: c[0])[1]
    return img.get("src")


def wprm_scrape(html, url=None):
    """
    Handles WP Recipe Maker (WPRM) print pages (URL pattern
    /wprm_print/<slug>, common across a huge share of WordPress recipe
    blogs) -- these carry no schema.org Recipe markup and no <h1>-<h4>
    headings at all, just WPRM's own well-labeled class structure
    (wprm-recipe-ingredient, wprm-recipe-instruction-text, ...). Since
    that's specific and unambiguous, this is tried as its own strategy
    rather than folded into the generic heuristic parser.

    Returns None if no wprm-recipe-ingredients/instructions block is found,
    so callers can fall through to the next strategy.
    """
    soup = BeautifulSoup(html, "html.parser")

    ingredients_list = soup.find(class_="wprm-recipe-ingredients")
    instructions_list = soup.find(class_="wprm-recipe-instructions")
    if not ingredients_list and not instructions_list:
        return None

    ingredients = []
    if ingredients_list:
        for li in ingredients_list.select("li.wprm-recipe-ingredient"):
            parts = []
            for field in ("ingredient-amount", "ingredient-unit", "ingredient-name", "ingredient-notes"):
                text = _wprm_field_value(li, field)
                if text:
                    parts.append(text)
            line = " ".join(parts)
            if line:
                ingredients.append(line)

    instructions = []
    if instructions_list:
        for li in instructions_list.select("li.wprm-recipe-instruction"):
            text_el = li.find(class_="wprm-recipe-instruction-text")
            text = text_el.get_text(" ", strip=True) if text_el else li.get_text(" ", strip=True)
            if text:
                instructions.append({"@type": "HowToStep", "text": clean_step_text(text)})

    name_el = soup.find(class_="wprm-recipe-name")
    summary_el = soup.find(class_="wprm-recipe-summary")
    author = _wprm_field_value(soup, "author")

    servings = _wprm_field_value(soup, "servings")
    servings_unit = _wprm_field_value(soup, "servings-unit")
    recipe_yield = f"{servings} {servings_unit}".strip() if servings else None

    prep_minutes = _wprm_minutes(soup, "prep_time")
    cook_minutes = _wprm_minutes(soup, "cook_time")
    # WPRM lets a recipe define an arbitrary extra time field with its own
    # label (e.g. "Freeze Time", "Rest Time", "Marinate Time") -- schema.org
    # Recipe has no slot for that concept at all. cookTime is the closest
    # semantic fit (some inactive-but-necessary step between prep and
    # serving), so it's used as a fallback only when the recipe has no
    # real cook_time of its own to conflict with.
    if cook_minutes is None:
        cook_minutes = _wprm_minutes(soup, "custom_time")
    total_minutes = _wprm_minutes(soup, "total_time")

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": name_el.get_text(strip=True) if name_el else None,
        "description": summary_el.get_text(" ", strip=True) if summary_el else None,
        "author": {"@type": "Person", "name": author} if author else None,
        "image": [_wprm_best_image(soup)] if _wprm_best_image(soup) else None,
        "recipeYield": recipe_yield,
        "prepTime": iso8601_duration(prep_minutes),
        "cookTime": iso8601_duration(cook_minutes),
        "totalTime": iso8601_duration(total_minutes),
        "recipeIngredient": ingredients,
        "recipeInstructions": instructions,
        "url": url,
    }
    return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}


def br_line_scrape(html, url=None):
    """
    Handles pages with no real list markup for the ingredients at all --
    just bare lines of text separated by <br> tags, sometimes with broken
    tag nesting (e.g. an unclosed <em>) that traps genuine step-by-step
    <li> instructions inside what looks like a single paragraph. Common on
    low-effort or AI-templated "recipe-shaped" content, but the detection
    itself doesn't assume that -- it just looks for a run of lines that are
    mostly shaped like ingredient quantities (see INGREDIENT_LINE_RE),
    regardless of heading text or list markup.

    For each <p> on the page: split its content into "lines" at each <br>
    (via _walk_line_stream), stopping if a heading is encountered (that
    marks the start of an unrelated section that broken markup has nested
    inside this same <p>). Any <li> elements encountered along the way are
    collected separately as candidate instruction steps. If at least 3
    lines were found and at least half of them look like ingredient
    quantities, treat this as the recipe: keep only the quantity-shaped
    lines as ingredients (dropping stray intro/outro sentences) and use
    whatever <li> steps were collected as the instructions.

    Returns None if no <p> on the page matches this pattern.
    """
    soup = BeautifulSoup(html, "html.parser")

    for p in soup.find_all("p"):
        lines, steps, current = [], [], []
        for ev in _walk_line_stream(p):
            if ev[0] == "text":
                current.append(ev[1])
            elif ev[0] == "br":
                text = "".join(current).strip()
                if text:
                    lines.append(text)
                current = []
            elif ev[0] == "li":
                text = "".join(current).strip()
                if text:
                    lines.append(text)
                current = []
                step_text = ev[1].get_text(" ", strip=True)
                if step_text:
                    steps.append(step_text)
            elif ev[0] == "stop":
                break
        tail = "".join(current).strip()
        if tail:
            lines.append(tail)

        if len(lines) < 3:
            continue
        matching = [l for l in lines if INGREDIENT_LINE_RE.match(l)]
        if len(matching) < 3 or len(matching) / len(lines) < 0.5:
            continue

        title = extract_title(soup)
        image_url = find_best_image(soup, url=url)
        instructions = [{"@type": "HowToStep", "text": clean_step_text(s)} for s in steps]

        recipe = {
            "@context": "https://schema.org",
            "@type": "Recipe",
            "name": title,
            "image": [image_url] if image_url else None,
            "recipeIngredient": matching,
            "recipeInstructions": instructions,
            "url": url,
        }
        return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}

    return None


def mntl_structured_content_scrape(html, url=None):
    """
    Handles Dotdash Meredith 'Mantle' CMS how-to articles (Treehugger, The
    Spruce Eats, MNN-migrated content, etc.) that walk through a process
    step by step under a series of <h2> headings, with no schema.org Recipe
    markup and no ingredients list at all -- just narrative paragraphs (e.g.
    "Breading the Eggplant" / "Time to Fry" / "Put Them Into the Freezer").

    Detected via the page's 'structured-content' block (class
    'mntl-sc-block-heading' for headings, 'mntl-sc-block-html' for body
    paragraphs). Each heading + the paragraph(s) that follow it, up to the
    next heading, becomes one HowToStep. Ingredients are inferred from
    "(I used ...)" asides via extract_iused_ingredients() -- see that
    function's docstring for why this is necessarily incomplete.

    Returns None if no 'structured-content' block is found, so callers can
    fall through to the generic heuristic parser.
    """
    soup = BeautifulSoup(html, "html.parser")

    content = soup.find(class_=lambda c: c and "structured-content" in c.split())
    if not content:
        return None

    intro_paragraphs = []
    steps = []
    current_heading = None
    current_paragraphs = []

    def flush():
        if current_heading or current_paragraphs:
            text = clean_step_text(" ".join(current_paragraphs))
            if text:
                steps.append({"@type": "HowToStep", "name": current_heading, "text": text}
                             if current_heading else {"@type": "HowToStep", "text": text})

    for tag in content.find_all(["h2", "h3", "p"]):
        classes = tag.get("class") or []
        is_heading = any("mntl-sc-block-heading" in c for c in classes)
        is_body = any("mntl-sc-block-html" in c for c in classes)
        if is_heading:
            flush()
            current_heading = tag.get_text(strip=True)
            current_paragraphs = []
        elif is_body:
            text = tag.get_text(" ", strip=True)
            if not text:
                continue
            if current_heading is None:
                intro_paragraphs.append(text)
            else:
                current_paragraphs.append(text)
    flush()

    if not steps:
        return None

    all_body_text = " ".join(intro_paragraphs + [s.get("text", "") for s in steps])
    ingredients = extract_iused_ingredients(all_body_text)

    title = extract_title(soup)
    image_url = find_best_image(soup, url=url)
    description = " ".join(intro_paragraphs[:2]) if intro_paragraphs else None

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": title,
        "description": description,
        "image": [image_url] if image_url else None,
        "recipeIngredient": ingredients,
        "recipeInstructions": steps,
        "url": url,
    }
    return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}


# Matches a heading that IS a "Step N" label (not just contains one
# somewhere in a longer sentence), e.g. "Step 1: Preheat Your Air Fryer" --
# used by step_heading_scrape to find a run of these across a page.
STEP_HEADING_RE = re.compile(r"^step\s*\d+\b", re.IGNORECASE)


def render_reference_table(table):
    """
    Renders a simple 2-column reference table (e.g. "Burger Thickness |
    Cooking Time") as a short inline text summary -- one "left: right" pair
    per data row, joined by "; ". This is for tables that accompany a
    step's prose as supplementary reference data (pick a time based on
    thickness) rather than being a sequence of actions themselves (see
    extract_table_step_items for that other case). Skips a header row
    (detected via <th>); returns "" if a row doesn't have exactly 2 <td>
    cells, since that's outside what this simple rendering handles.
    """
    pairs = []
    for row in table.find_all("tr"):
        if row.find("th"):
            continue
        cells = row.find_all("td")
        if len(cells) != 2:
            continue
        left = cells[0].get_text(" ", strip=True)
        right = cells[1].get_text(" ", strip=True)
        if left and right:
            pairs.append(f"{left}: {right}")
    return "; ".join(pairs)


def step_heading_scrape(html, url=None):
    """
    Handles "how-to" articles that lay out their method as a series of
    "Step N: <short title>" headings (h1-h4), each immediately followed by
    one or more descriptive paragraphs, rather than a single
    Ingredients/Method section with real list markup, or Dotdash
    Meredith's specific 'structured-content' class (mntl_structured_content_scrape).
    This template shows up across a range of independently-run WordPress
    recipe/how-to blogs, not tied to any particular CMS or class naming.

    Requires at least 2 such "Step N" headings to trigger at all -- a
    single matching heading is too weak a signal on its own (could be an
    unrelated one-off heading that happens to start with "Step"). Returns
    None otherwise, so callers fall through to the next strategy.

    Doesn't attempt to find an ingredients list -- pages using this
    template are often about a single packaged/pre-made food item (frozen
    burgers, hot dogs, ...) with no real ingredients list to speak of at
    all, so recipeIngredient is simply left absent rather than guessed at.
    """
    soup = BeautifulSoup(html, "html.parser")

    step_headings = [
        h for h in soup.find_all(["h1", "h2", "h3", "h4"])
        if STEP_HEADING_RE.match(h.get_text(strip=True))
    ]
    if len(step_headings) < 2:
        return None

    steps = []
    for heading in step_headings:
        paragraphs = []
        for sib in heading.find_next_siblings():
            if sib.name in ("h1", "h2", "h3", "h4"):
                break
            if sib.name == "p":
                text = sib.get_text(" ", strip=True)
                if text:
                    paragraphs.append(text)
            elif sib.name == "table":
                table_text = render_reference_table(sib)
                if table_text:
                    paragraphs.append(table_text)
        text = clean_step_text(" ".join(paragraphs))
        if not text:
            continue
        name = STEP_LABEL_RE.sub("", heading.get_text(strip=True)).strip()
        step = {"@type": "HowToStep", "text": text}
        if name:
            step["name"] = name
        steps.append(step)

    if not steps:
        return None

    title = extract_title(soup)
    image_url = find_best_image(soup, url=url)

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": title,
        "image": [image_url] if image_url else None,
        "recipeInstructions": steps,
        "url": url,
    }
    return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}


def extract_title(soup):
    """
    Pick the recipe's title, robust to pages that have more than one
    heading-like element (e.g. a sitewide banner heading plus the actual
    recipe title) -- naively taking the first <h1> can grab the wrong one.

    Strategy: if there's exactly one <h1> on the page, trust it directly --
    it's almost certainly the actual editorial recipe title, even if it
    diverges completely from the <title> tag (which is often SEO-oriented
    and can differ in wording, e.g. a byline like "Curtis Stone's seared
    barramundi..." as the h1 vs "Seared Barramundi ... Recipe | Coles" as
    the title tag). With multiple <h1>s (e.g. a sitewide banner plus the
    real title), fall back to matching against <title>: the <title> tag
    reliably contains the real page title (often with a site-name
    prefix/suffix), and whichever <h1> text also appears in it is almost
    certainly the actual recipe title (a generic banner heading like
    "Baking Recipes" usually won't appear in <title> at all). Falls back
    further to <title> itself with a likely site-name segment stripped off.
    """
    title_tag = soup.find("title")
    title_text = title_tag.get_text(strip=True) if title_tag else None

    h1_texts = [h.get_text(strip=True) for h in soup.find_all("h1") if h.get_text(strip=True)]

    if len(h1_texts) == 1:
        return h1_texts[0]

    if title_text:
        # Prefer the longest matching h1 (most specific) that's actually
        # contained in the <title> text.
        matches = [h for h in h1_texts if h in title_text]
        if matches:
            return max(matches, key=len)
        # No h1 matched the <title> at all (e.g. the only h1 is an
        # unrelated sitewide banner) -- try to strip a likely site-name
        # segment off the <title> itself rather than trusting that h1.
        for sep in (" - ", " | ", ": "):
            if sep in title_text:
                parts = [p.strip() for p in title_text.split(sep) if p.strip()]
                if parts:
                    return max(parts, key=len)  # longer segment is usually the real title, not the site name
        return title_text

    if h1_texts:
        return h1_texts[0]

    return None


# Matches a short "<label>: <number> <unit>" heading/blurb commonly used
# by templated recipe sites for timing info (e.g. "Preparation time: 30
# minutes", "Cook Time: 1 hour", "Total time: 45 mins") -- one pattern per
# schema.org Recipe timing field. Not tied to any particular heading tag;
# used against short text snippets from whatever candidate tags the caller
# is already scanning.
TIME_LABEL_PATTERNS = {
    "prepTime": re.compile(
        r"pre(?:p|paration)\s*time\s*:?\s*(\d+)\s*(hours?|hrs?|h\b|minutes?|mins?|m\b)",
        re.IGNORECASE,
    ),
    "cookTime": re.compile(
        r"cook(?:ing)?\s*time\s*:?\s*(\d+)\s*(hours?|hrs?|h\b|minutes?|mins?|m\b)",
        re.IGNORECASE,
    ),
    "totalTime": re.compile(
        r"total\s*time\s*:?\s*(\d+)\s*(hours?|hrs?|h\b|minutes?|mins?|m\b)",
        re.IGNORECASE,
    ),
}


def find_time_fields(soup):
    """
    Scan short heading/label-like tags for "Prep time: 30 minutes"-style
    text and return {"prepTime": "PT30M", ...} for whichever of
    prepTime/cookTime/totalTime are found (ISO 8601 duration strings, via
    iso8601_duration). Not schema.org markup -- just a plain-text label a
    templated site renders next to the recipe -- so this has to be found
    by pattern rather than any specific tag/class.
    """
    result = {}
    for tag in soup.find_all(["h1", "h2", "h3", "h4", "b", "strong", "p", "td"]):
        text = tag.get_text(" ", strip=True)
        if not text or len(text) > 60:
            continue
        for field, pattern in TIME_LABEL_PATTERNS.items():
            if field in result:
                continue
            m = pattern.search(text)
            if not m:
                continue
            qty, unit = int(m.group(1)), m.group(2).lower()
            minutes = qty * 60 if unit.startswith("h") else qty
            result[field] = iso8601_duration(minutes)
    return result


# Matches a short "Difficulty: Easy" (or Medium/Hard/Intermediate/...)
# label, another common templated-recipe-site blurb with no schema.org
# equivalent. Recipe has no dedicated difficulty property (and neither
# does Nextcloud Cookbook's own API model) -- keywords is the closest fit,
# since it's the one freeform tagging field both support.
DIFFICULTY_LABEL_RE = re.compile(r"difficulty\s*:?\s*([A-Za-z][A-Za-z\s\-]{0,20}?)\s*$", re.IGNORECASE)


def find_difficulty_tag(soup):
    """Look for a short 'Difficulty: <level>' label and return just the
    level (e.g. 'Easy'), or None if no such label is found."""
    for tag in soup.find_all(["h1", "h2", "h3", "h4", "b", "strong", "p", "td"]):
        text = tag.get_text(" ", strip=True)
        if not text or len(text) > 60:
            continue
        m = DIFFICULTY_LABEL_RE.search(text)
        if m:
            return m.group(1).strip()
    return None


# Matches a heading whose *entire* text is a notes-style label (e.g.
# "NOTES & INSPIRATION", "Notes", "Tips") -- not just a heading that
# happens to mention "notes" somewhere, which would also match an
# ordinary ingredient/step line like "10g baking powder (see notes)".
NOTES_HEADING_RE = re.compile(
    r"^(?:notes?|tips?|inspiration)(?:\s*(?:&|and)\s*(?:notes?|tips?|inspiration))*$",
    re.IGNORECASE,
)


def find_notes_section(soup, max_paragraphs=10):
    """
    Looks for a heading like "Notes & Inspiration" or "Tips" and collects
    the plain <p> paragraphs that follow it as supplementary notes text,
    meant to be folded into the recipe's description. Stops at the next
    heading of any kind, or after max_paragraphs -- a generous but firm
    cap, since these sections can run straight into unrelated
    footer/related-content on some pages with no heading of its own to
    mark where the real notes end. Returns None if no matching heading is
    found, or if it isn't followed by any real paragraph content.
    """
    for tag in soup.find_all(["h1", "h2", "h3", "h4", "b", "strong", "p"]):
        label = tag.get_text(strip=True)
        if not NOTES_HEADING_RE.match(label):
            continue
        heading_p = tag if tag.name == "p" else tag.find_parent("p")
        if heading_p is None:
            continue

        notes = []
        for sib in heading_p.find_next_siblings():
            if sib.name is None:
                continue  # stray NavigableString between tags
            if sib.name != "p" or len(notes) >= max_paragraphs:
                break  # a heading, a real list/divider, or the cap -- stop here
            text = sib.get_text(" ", strip=True)
            if text:
                notes.append(text)

        if notes:
            return "\n".join(notes)
    return None


def extract_table_step_items(table):
    """
    Some sites present a short numbered step sequence as a 2-column table
    (e.g. a header row "Step | Action" followed by one row per step)
    instead of a <ul>/<ol>. Extracts one item per data row: if the row's
    first cell is purely numeric (a step number) the second cell's text is
    used as the step; otherwise the whole row's cells are joined. Header
    rows (using <th>) are skipped.
    """
    items = []
    for row in table.find_all("tr"):
        if row.find("th"):
            continue
        cells = row.find_all("td")
        if not cells:
            continue
        texts = [c.get_text(" ", strip=True) for c in cells]
        if len(texts) >= 2 and re.match(r"^\d+$", texts[0].strip()):
            text = texts[-1]
        else:
            text = " ".join(t for t in texts if t)
        if text:
            items.append(text)
    return items


# Headings matching these are treated as the end of the recipe's actual
# instructions when scanning past the first matching heading for more step
# content (see find_instructions_zone) -- common follow-up sections
# (cleaning the appliance, tips, serving suggestions, an FAQ) that often
# reuse words like "steps" or "instructions" themselves but aren't part of
# the cooking method.
INSTRUCTIONS_ZONE_EXCLUDE_KEYWORDS = (
    "clean", "tip", "faq", "conclusion", "serving", "topping", "storage", "nutrition",
)


def heuristic_scrape(html, url=None):
    """
    Best-effort extractor for pages with no schema.org/JSON-LD/microdata at
    all (common on older, hand-coded recipe sites, but also modern
    client-rendered sites whose Recipe JSON-LD wasn't captured in a static
    save). This looks for a heading containing 'ingredient' and a heading
    containing 'method'/'instructions'/'directions'. If a real <ul>/<ol>
    list sits nearby, its <li> items are used directly (the common case on
    modern templated sites); otherwise it falls back to grabbing the text
    that follows the heading and crudely splitting it, up to the next
    heading (the common case on older, hand-coded pages with no real list
    markup at all). It won't be as reliable as real structured data, so
    review the output before publishing it.
    """
    soup = BeautifulSoup(html, "html.parser")

    # BeautifulSoup's get_text() includes text inside <script>/<style> tags
    # by default. Some site builders (Squarespace notably) inject per-block
    # <style> tags directly inside content containers, right alongside the
    # actual text, so without this those CSS rules get swept into
    # ingredients/instructions verbatim. Nothing in this heuristic parser
    # ever wants script/style content, so strip it up front.
    for tag in soup.find_all(["script", "style"]):
        tag.decompose()

    # Many recipe sites attach a legal/nutrition disclaimer block right
    # next to (or even inside) the ingredients list (e.g. "Nutritional
    # analysis is an estimate only..."). It's not part of the recipe, but
    # it sits close enough to get swept in by both the list-based and
    # text-blob-based extraction below if left in place -- so drop it
    # outright, the same way script/style are dropped above.
    for tag in soup.find_all(class_=lambda c: c and "disclaimer" in c.lower()):
        tag.decompose()

    title = extract_title(soup)

    # Keywords for every section type this parser recognizes, used so a
    # scan for one section stops when it bumps into the start of another.
    SECTION_KEYWORD_GROUPS = {
        "ingredients": ["ingredient"],
        "instructions": ["method", "instructions", "directions", "steps"],
    }

    SENTENCE_SPLIT_RE = re.compile(r"(?<=[.!?])\s+(?=[A-Z])")

    def find_list_section(keywords, other_keyword_groups, strip_step_label=False, split_sentences=False):
        """
        Modern templated recipe sites usually mark ingredients/instructions
        up as a real <ul>/<ol> of <li> items next to a matching heading,
        even when (as here) the page's Recipe JSON-LD wasn't captured.
        Preferring that real list over crudely splitting a blob of text
        avoids mangling entries like "6 (about 100g each) Barramundi..."
        into fragments at every digit+unit boundary. Returns a list of
        item strings, or None if no such list is found near a matching
        heading (letting the caller fall back to the text-blob approach).

        Some sites pack several distinct actions into a single <li> (e.g.
        one numbered "Step 4" covering nine sentences' worth of searing,
        flipping, resting, and repeating in batches) -- not usable as one
        step in a cook-along context. split_sentences breaks each <li>'s
        text into one item per sentence, same as the text-blob path
        already does; left off for ingredients, where a line can
        legitimately contain a comma-separated clause but was never meant
        to be split into several separate ingredient lines.
        """
        for tag in soup.find_all(["b", "strong", "h1", "h2", "h3", "h4", "p", "td"]):
            label = tag.get_text(strip=True).lower()
            if not (any(kw in label for kw in keywords) and len(label) < 40):
                continue

            list_tag = None
            for candidate in tag.find_all_next(["ul", "ol", "h1", "h2", "h3", "h4"]):
                if candidate.name in ("ul", "ol"):
                    list_tag = candidate
                    break
                # A heading with real text marks a genuine section boundary
                # (whether or not it's the "other" section's own heading)
                # -- stop looking. An empty/decorative sub-heading (e.g. an
                # unlabeled ingredient-category title) isn't a boundary --
                # skip over it and keep looking for the list that follows.
                if candidate.get_text(strip=True):
                    break
            if not list_tag:
                continue

            items = []
            for li in list_tag.find_all("li", recursive=False):
                text = li.get_text(" ", strip=True)
                if strip_step_label:
                    text = STEP_LABEL_RE.sub("", text)
                if not text:
                    continue
                if split_sentences:
                    items.extend(s.strip() for s in SENTENCE_SPLIT_RE.split(text) if s.strip())
                else:
                    items.append(text)
            if items:
                return items
        return None

    def is_group_label_paragraph(p):
        """
        Distinguishes a group-label paragraph (e.g. 'FOR THE SPELT FLOUR
        VERSION:', 'GLUTEN-FREE VERSION') from an ordinary ingredient/step
        paragraph that just happens to have some bold text in it (e.g.
        '**Preheat** your oven to...', where only the leading verb is
        bold). Requires the *entire* paragraph's text to be both fully
        uppercase and exactly match a single bold element's text -- not
        just contain some bold text -- since a real step's partial bolding
        wouldn't satisfy either condition on its own.
        """
        text = p.get_text(strip=True)
        if not text or len(text) > 60 or not text.isupper():
            return False
        bold = p.find(["strong", "b"])
        return bool(bold) and bold.get_text(strip=True) == text

    def find_paragraph_section(keywords, other_keyword_groups, capture_yield=False, split_sentences=False):
        """
        Some sites (e.g. Shopify blog articles built with a simple
        rich-text editor) lay out ingredients/instructions as a run of
        individual <p> tags -- one item per paragraph -- rather than real
        <ul>/<ol> markup or a single blob of text. Finds a heading
        matching `keywords`, then walks its following sibling <p> tags
        directly, treating each one as an already-complete, atomic item
        (no further splitting attempted, unlike the cruder text-blob
        fallback below, which can incorrectly re-split an already-atomic
        item like "180 g (1 1/2 cups) brown rice flour" by mistaking its
        parenthetical cup equivalent for a second, separate ingredient).

        A fully-bold, fully-uppercase paragraph (see
        is_group_label_paragraph) is treated as a group label and folded
        into each subsequent item's text (e.g. "... (Gluten-Free
        Version)") rather than being returned as an item itself. Stops at
        the next heading belonging to `other_keyword_groups`, or at any
        non-<p> sibling (a real list or divider suggests this isn't the
        simple paragraph-per-item pattern after all).

        If capture_yield is set, a leading "Makes 12"/"Serves 4"-style
        paragraph is captured as a (item_list, recipe_yield) tuple instead
        of being treated as an item. If split_sentences is set, a single
        paragraph containing multiple sentences is split into one item
        per sentence -- needed for pages where malformed markup (e.g. an
        illegal "<br>...</br>" pair) traps everything in just one <p>, so
        there's only ever one "sibling" to find in the first place, no
        matter how many real steps it actually contains. Returns None if
        no heading matches `keywords`, or if it isn't followed by at
        least one real item.
        """
        other_keywords = [kw for group in other_keyword_groups for kw in group]

        for tag in soup.find_all(["b", "strong", "h1", "h2", "h3", "h4", "p", "td"]):
            label = tag.get_text(strip=True).lower()
            if not (any(kw in label for kw in keywords) and len(label) < 40):
                continue

            heading_p = tag if tag.name == "p" else tag.find_parent("p")
            if heading_p is None:
                continue

            items = []
            recipe_yield = None
            current_group = None
            for sib in heading_p.find_next_siblings():
                if sib.name is None:
                    continue  # stray NavigableString between tags
                if sib.name != "p":
                    break  # a real list/divider -- not this pattern after all

                sib_label = sib.get_text(" ", strip=True).lower()
                if any(kw in sib_label for kw in other_keywords) and len(sib_label) < 40:
                    break  # reached the next section's own heading

                if capture_yield and recipe_yield is None and re.match(
                    r"^(makes|serves|yields?)\b", sib.get_text(" ", strip=True), re.IGNORECASE
                ):
                    sib_text = sib.get_text(" ", strip=True)
                    split_match = YIELD_INGREDIENT_SPLIT_RE.match(sib_text)
                    if split_match:
                        # The source ran a yield statement and the first
                        # ingredient together on one line with no
                        # separator -- recover the ingredient rather than
                        # losing it inside the yield value.
                        recipe_yield = clean_recipe_yield(split_match.group(1))
                        text = split_match.group(2)
                        if current_group:
                            text = f"{text} ({current_group.title()})"
                        items.append(text)
                    else:
                        recipe_yield = clean_recipe_yield(sib_text)
                    continue

                if is_group_label_paragraph(sib):
                    current_group = sib.get_text(" ", strip=True).rstrip(":").strip()
                    continue

                text = sib.get_text(" ", strip=True)
                if not text:
                    continue
                parts = (
                    [s.strip() for s in SENTENCE_SPLIT_RE.split(text) if s.strip()]
                    if split_sentences else [text]
                )
                for part in parts:
                    if current_group:
                        part = f"{part} ({current_group.title()})"
                    items.append(part)

            if items:
                return (items, recipe_yield) if capture_yield else items
        return None

    def find_instructions_zone(keywords, exclude_keywords, page_title):
        """
        Some "recipe guide" articles split their actual cooking process
        across several consecutive sub-headings rather than one single
        Instructions section -- e.g. "Preparation Steps" (2 items), then
        "Air Fryer Setup" (2 items, heading doesn't even mention "steps"),
        then a "Step | Action" table under yet another heading. Taking
        only the first matching heading's list (as find_list_section does)
        misses the rest of the method entirely.

        However, looking *past* the first heading's list is genuinely
        risky: many recipe pages follow their real Method section with
        unrelated content -- a "related recipes" carousel, for instance --
        laid out as more <ul>/<li> markup with no heading of its own in
        between to signal a stop. So this only even attempts to look
        further when the first heading's own list looks suspiciously
        incomplete (fewer than MIN_CONFIDENT_ITEMS items) -- a real,
        self-contained method (e.g. 20+ sentence-split steps) never
        triggers the extended search at all, which is what actually keeps
        this safe, not the stop conditions below (those help, but aren't
        sufcient by themselves against unheaded carousel content).

        When the gate does open, keeps walking forward collecting items
        from subsequent <ul>/<ol>/<table>s, skipping over headings with no
        list/table directly following them, until:
          - a heading matches one of `exclude_keywords`, or ends in "?"
            (common follow-up sections this isn't part of: cleaning the
            appliance, tips, serving suggestions, storage, an FAQ),
          - a heading's text exactly repeats the page's own title -- a
            strong signal of a duplicated/repeated "recipe card" section
            elsewhere on the page, rather than more of the actual method,
          - a small, firm cap on extra headings traversed is hit, or
          - enough items have been accumulated that this no longer looks
            like a suspiciously incomplete list.
        Returns None if no heading matches `keywords` at all.
        """
        MIN_CONFIDENT_ITEMS = 4
        MAX_EXTRA_HEADINGS = 3
        MAX_HEADING_SKIPS = 6

        for tag in soup.find_all(["b", "strong", "h1", "h2", "h3", "h4", "p", "td"]):
            label = tag.get_text(strip=True).lower()
            if not (any(kw in label for kw in keywords) and len(label) < 40):
                continue

            list_tag = None
            for candidate in tag.find_all_next(["ul", "ol", "table", "h1", "h2", "h3", "h4"]):
                if candidate.name in ("ul", "ol", "table"):
                    list_tag = candidate
                    break
                if candidate.get_text(strip=True):
                    break  # a genuine (non-empty) heading with no list -- give up on this match
            if list_tag is None:
                continue

            def extract(list_or_table):
                if list_or_table.name == "table":
                    return extract_table_step_items(list_or_table)
                found = []
                for li in list_or_table.find_all("li", recursive=False):
                    text = STEP_LABEL_RE.sub("", li.get_text(" ", strip=True))
                    if text:
                        found.extend(s.strip() for s in SENTENCE_SPLIT_RE.split(text) if s.strip())
                return found

            items = extract(list_tag)
            if len(items) >= MIN_CONFIDENT_ITEMS:
                return items  # looks like a complete method already -- don't go looking further

            # The first list looked short -- cautiously check a few more
            # headings in case the method continues under a separate
            # sub-heading, same idea as above but bounded much more
            # tightly, and only reached at all for suspiciously-short lists.
            cursor = list_tag
            heading_skips = 0
            lists_consumed = 0
            while heading_skips < MAX_HEADING_SKIPS and lists_consumed < MAX_EXTRA_HEADINGS:
                nxt = cursor.find_next(["ul", "ol", "table", "h1", "h2", "h3", "h4"])
                if nxt is None:
                    break
                if nxt.name in ("h1", "h2", "h3", "h4"):
                    heading_text = nxt.get_text(strip=True)
                    if not heading_text:
                        cursor = nxt
                        continue
                    heading_label = heading_text.lower()
                    if (
                        heading_text.endswith("?")
                        or any(kw in heading_label for kw in exclude_keywords)
                        or (page_title and heading_text == page_title)
                    ):
                        break
                    heading_skips += 1
                    cursor = nxt
                    continue
                items.extend(extract(nxt))
                lists_consumed += 1
                cursor = nxt

            if items:
                return items
        return None

    def get_row_container(tag):
        """The block-level container to treat as one 'row' of content: the
        enclosing table row if there is one, otherwise the nearest
        td/li/p/div ancestor."""
        return tag.find_parent("tr") or tag.find_parent(["td", "li", "p", "div"]) or tag

    def find_section(keywords, other_keyword_groups):
        other_keywords = [kw for group in other_keyword_groups for kw in group]
        for tag in soup.find_all(["b", "strong", "h2", "h3", "h4", "p", "td"]):
            label = tag.get_text(strip=True).lower()
            if not (any(kw in label for kw in keywords) and len(label) < 40):
                continue

            row = get_row_container(tag)
            collected = []

            # The row's own text covers the common inline case, e.g.
            # "<b>Ingredients:</b> 175g butter...". Strip the label prefix.
            own_text = row.get_text("\n", strip=True)
            own_text = re.sub(
                r"^\s*(ingredients|method|directions|instructions)\s*:?\s*",
                "",
                own_text,
                flags=re.IGNORECASE,
            )
            if own_text:
                collected.append(own_text)

            # Some sites put the label in its own row/cell and the actual
            # content in the row(s) that follow -- walk forward to catch
            # that layout too, stopping when we hit the next section's own
            # heading (detected via an actual heading-like tag, not by how
            # long the sibling's merged text happens to be).
            def sibling_starts_next_section(sib):
                tags_to_check = [sib] if sib.name in ("b", "strong", "h2", "h3", "h4") else []
                tags_to_check += sib.find_all(["b", "strong", "h2", "h3", "h4"])
                for t in tags_to_check:
                    lbl = t.get_text(strip=True).lower()
                    if any(kw in lbl for kw in other_keywords) and len(lbl) < 40:
                        return True
                return False

            for sib in row.find_next_siblings(limit=6):
                if sibling_starts_next_section(sib):
                    break
                sib_text = sib.get_text("\n", strip=True)
                if sib_text:
                    collected.append(sib_text)
                    break  # old-style sites usually put all content in one following row

            text = "\n".join(c for c in collected if c)
            if text:
                return text
        return None

    image_url = find_best_image(soup, url=url)
    time_fields = find_time_fields(soup)
    difficulty = find_difficulty_tag(soup)
    notes = find_notes_section(soup)

    ingredients = find_list_section(
        SECTION_KEYWORD_GROUPS["ingredients"], [SECTION_KEYWORD_GROUPS["instructions"]]
    )
    recipe_yield = None
    if ingredients is None:
        paragraph_result = find_paragraph_section(
            SECTION_KEYWORD_GROUPS["ingredients"], [SECTION_KEYWORD_GROUPS["instructions"]],
            capture_yield=True,
        )
        if paragraph_result is not None:
            ingredients, recipe_yield = paragraph_result

    if ingredients is None:
        ingredients_text = find_section(
            SECTION_KEYWORD_GROUPS["ingredients"], [SECTION_KEYWORD_GROUPS["instructions"]]
        )
        ingredients = split_ingredient_block(ingredients_text) if ingredients_text else []

        # A leading line like "Makes one 18 cm cake" or "Serves 4" is a
        # yield statement, not an ingredient -- pull it out if present.
        # (Only relevant to the text-blob path: a real ingredients <li>
        # list doesn't have stray yield statements mixed into it.)
        if ingredients and re.match(r"^(makes|serves|yields?)\b", ingredients[0], re.IGNORECASE):
            recipe_yield = clean_recipe_yield(ingredients.pop(0))

    instructions_list = find_instructions_zone(
        SECTION_KEYWORD_GROUPS["instructions"], INSTRUCTIONS_ZONE_EXCLUDE_KEYWORDS, title,
    )
    if instructions_list is None:
        instructions_list = find_paragraph_section(
            SECTION_KEYWORD_GROUPS["instructions"], [SECTION_KEYWORD_GROUPS["ingredients"]],
            split_sentences=True,
        )
    if instructions_list is not None:
        instructions = [
            {"@type": "HowToStep", "text": clean_step_text(s)} for s in instructions_list
        ]
    else:
        method_text = find_section(
            SECTION_KEYWORD_GROUPS["instructions"], [SECTION_KEYWORD_GROUPS["ingredients"]]
        )
        instructions = []
        if method_text:
            # Split on sentence boundaries as a rough step approximation
            steps = re.split(r"(?<=[.!?])\s+(?=[A-Z])", method_text)
            instructions = [
                {"@type": "HowToStep", "text": clean_step_text(s)} for s in steps if s.strip()
            ]

    recipe = {
        "@context": "https://schema.org",
        "@type": "Recipe",
        "name": title,
        "description": f"Notes:\n{notes}" if notes else None,
        "image": [image_url] if image_url else None,
        "recipeYield": recipe_yield,
        "prepTime": time_fields.get("prepTime"),
        "cookTime": time_fields.get("cookTime"),
        "totalTime": time_fields.get("totalTime"),
        "keywords": merge_keywords(difficulty),
        "recipeIngredient": ingredients,
        "recipeInstructions": instructions,
        "url": url,
    }
    return {k: v for k, v in recipe.items() if v not in (None, "", [], {})}


def scrape_from_url(url):
    # scrape_me() in current recipe-scrapers versions doesn't forward extra
    # kwargs like wild_mode, so fetch the HTML ourselves and call
    # scrape_html() directly.
    request = Request(url, headers={"User-Agent": USER_AGENT})
    html = urlopen(request).read().decode("utf-8", errors="replace")
    scraper = scrape_html(html=html, org_url=url, wild_mode=True)
    return build_recipe_jsonld(scraper, canonical_url=url)


def scrape_from_file(path, url=None):
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        html = f.read()
    scraper = scrape_html(html=html, org_url=url or "https://example.com", wild_mode=True)
    return build_recipe_jsonld(scraper, canonical_url=url)


def upload_to_nextcloud(recipe_json, nextcloud_url, username, password):
    """
    Upload a recipe to Nextcloud Cookbook via the API.

    Args:
        recipe_json: The schema.org Recipe dict produced by this script
        nextcloud_url: Base Nextcloud URL (e.g., "https://nextcloud.example.com")
        username: Nextcloud username
        password: Nextcloud password (or app password)

    Returns:
        The ID of the newly created recipe (str).

    Raises:
        HTTPError or URLError on network issues.
    """
    # create_recipe() expects an actual nextcloud_cookbook_api Recipe model,
    # not a plain dict -- it calls recipe.model_dump(...) internally, which
    # a dict doesn't have. The Cookbook API's own field names/types also
    # differ from schema.org's, so we need to translate rather than pass
    # our JSON-LD straight through:
    #   - recipeInstructions here is a list of {"@type": "HowToStep", "text": ...}
    #     dicts; Cookbook wants a plain list[str].
    #   - recipeYield here is free text (e.g. "4 servings" or a yield note);
    #     Cookbook wants an int number of servings.
    #   - id/date_created/date_modified are required by the model but are
    #     assigned by the server on creation, so we fill in placeholders.
    #   - nutrition is a required field on the model; we send an empty one
    #     if we don't have real nutrition data.
    instructions = [
        step.get("text", "") if isinstance(step, dict) else str(step)
        for step in recipe_json.get("recipeInstructions", [])
    ]

    yield_match = re.search(r"\d+", str(recipe_json.get("recipeYield", "")))
    servings = int(yield_match.group()) if yield_match else 1

    image = recipe_json.get("image", "")
    if isinstance(image, list):
        image = image[0] if image else ""

    # model_construct() bypasses the model's normal validators (including
    # the one that parses a comma-separated keywords string into a list),
    # so do that conversion ourselves rather than passing the raw string
    # through -- otherwise it gets serialized character-by-character.
    keywords_raw = recipe_json.get("keywords")
    if isinstance(keywords_raw, str):
        keywords = [k.strip() for k in keywords_raw.split(",") if k.strip()]
    elif isinstance(keywords_raw, list):
        keywords = keywords_raw
    else:
        keywords = None

    now = datetime.now(timezone.utc)

    recipe = Recipe.model_construct(
        id="",  # server assigns the real ID on creation
        name=recipe_json.get("name", ""),
        keywords=keywords,
        date_created=now,
        date_modified=now,
        image_url=image,
        image_placeholder_url="",
        type="Recipe",
        prep_time=recipe_json.get("prepTime"),
        cook_time=recipe_json.get("cookTime"),
        total_time=recipe_json.get("totalTime"),
        description=recipe_json.get("description", ""),
        url=recipe_json.get("url", ""),
        image=image,
        servings=servings,
        category=recipe_json.get("recipeCategory", ""),
        tools=[],
        ingredients=recipe_json.get("recipeIngredient", []),
        instructions=instructions,
        nutrition=Nutrition.model_construct(type="NutritionInformation"),
    )

    # CookbookClient.create_recipe() is NOT used here despite building a
    # correctly-typed Recipe model above -- its internal HTTP requests use
    # a leading-slash path (e.g. "/apps/cookbook/api/v1/recipes"), which
    # standard URL-joining rules (RFC 3986, used by requests/httpx) treat
    # as root-relative: it replaces the entire path of base_url, silently
    # discarding anything after the domain. Confirmed by comparing actual
    # request traffic: the Android app (which explicitly includes
    # /index.php in every request) succeeds, while this library's request
    # 404s with /index.php stripped, regardless of what's passed as
    # base_url -- so no --nextcloud-url value can fix this from the
    # outside. Sending the request directly avoids the broken URL join
    # while still reusing the library's own (correctly-typed) Recipe model
    # and its standard pydantic JSON serialization.
    payload = recipe.model_dump(mode="json", by_alias=True, exclude_none=True)
    create_url = nextcloud_url.rstrip("/") + "/index.php/apps/cookbook/api/v1/recipes"

    response = requests.post(
        create_url,
        json=payload,
        auth=(username, password),
        headers={"Content-Type": "application/json"},
    )

    if response.status_code in (401, 403):
        print(
            "Authentication failed (HTTP "
            f"{response.status_code}). If 2-factor authentication is enabled on this "
            "Nextcloud account, --nextcloud-pass must be an app password, "
            "not the account's normal login password -- generate one at: "
            "Nextcloud web UI > Settings > Security > Devices & sessions > "
            "\"Create new app password\".",
            file=sys.stderr,
        )
        sys.exit(1)
    response.raise_for_status()

    # The Cookbook API returns the full created recipe object; the ID we
    # want is nested under "id" -- if this shape turns out to be wrong for
    # your server's API version, print(response.json()) to see the actual
    # response and adjust this line accordingly.
    new_recipe_id = response.json().get("id")
    print(f"Created recipe '{recipe.name}' with ID: {new_recipe_id}")
    return new_recipe_id


def main():
    parser = argparse.ArgumentParser(
        description="Convert a recipe page to schema.org Recipe JSON-LD and optionally upload to Nextcloud"
    )
    parser.add_argument("--url", help="Recipe URL (fetched live, or used as canonical URL with --file)")
    parser.add_argument("--file", help="Path to a locally saved HTML file (avoids live fetching)")
    parser.add_argument("--out", help="Output file path (defaults to stdout). Skips Nextcloud upload if provided.")
    parser.add_argument("--nextcloud-url", help="Nextcloud instance URL (e.g., https://nextcloud.example.com)")
    parser.add_argument("--nextcloud-user", help="Nextcloud username")
    parser.add_argument(
        "--nextcloud-pass",
        help="Nextcloud app password (Settings > Security > Devices & sessions > "
             "\"Create new app password\"). Required, not just recommended, if the "
             "account has 2-factor authentication enabled -- the normal account "
             "password is rejected outright in that case.",
    )
    parser.add_argument(
        "--use-venv", action="store_true",
        help="Create/reuse a dedicated virtual environment for this script's dependencies, "
             "upgrade them there, and re-run inside it. Handled before argument parsing "
             "normally runs; see this file's module docstring.",
    )
    args = parser.parse_args()

    if not args.url and not args.file:
        parser.error("Provide --url (to fetch live) or --file (+ optional --url for canonical URL)")

    recipe_json = None
    if args.file:
        with open(args.file, encoding="utf-8", errors="replace") as f:
            file_content = f.read()
        recipe_json = try_load_existing_jsonld(file_content)
        if recipe_json:
            print("Detected an existing recipe JSON-LD file; reusing it directly "
                  "instead of re-scraping it.", file=sys.stderr)

        if not args.url:
            detected_url = detect_canonical_url(file_content)
            if detected_url:
                print(f"No --url given; using the canonical URL found in the file's own "
                      f"HTML instead: {detected_url}", file=sys.stderr)
                args.url = detected_url

    def run_fallback_chain():
        """
        Tries each strategy in turn -- embedded app-state JSON blobs, WPRM
        print pages, various how-to article patterns, then the generic
        heuristic parser -- accumulating a best-so-far result and merging
        in whichever of recipeIngredient/recipeInstructions is still
        missing from each subsequent strategy, rather than stopping at
        the very first strategy that finds anything at all.

        This matters because a page's ingredients and instructions can
        each need a different strategy to find correctly. For example,
        br_line_scrape is built to find bare <br>-separated ingredient
        lines with no real list markup, but only ever looks at <li>
        elements for instructions -- so a page whose instructions are
        ALSO bare <br>-separated prose (rather than <li>s) would
        otherwise come back with ingredients but no instructions at all,
        even though a later strategy (heuristic_scrape) might well find
        the instructions just fine. Stops early and returns as soon as
        both fields are filled, rather than running every remaining
        strategy unnecessarily.

        Returns None if nothing usable is found by any strategy (rather
        than exiting directly), so the caller can decide whether that's a
        hard failure or an acceptable outcome -- e.g. recipe-scrapers may
        have already found a usable partial result elsewhere that should
        be kept.
        """
        html = None
        try:
            html = open(args.file, encoding="utf-8", errors="replace").read() if args.file else fetch_html(args.url)
        except Exception as e_html:
            print(f"Could not read/fetch HTML: {e_html}", file=sys.stderr)
            return None

        def is_complete(r):
            return bool(r) and bool(r.get("recipeIngredient")) and bool(r.get("recipeInstructions"))

        MERGEABLE_FIELDS = (
            "recipeIngredient", "recipeInstructions", "description", "image",
            "recipeYield", "prepTime", "cookTime", "totalTime", "keywords",
            "recipeCategory", "recipeCuisine", "author",
        )

        def merge_in(best, result, message):
            """Merges any of MERGEABLE_FIELDS from result into best
            wherever best is still missing them (not just
            recipeIngredient/recipeInstructions -- a later strategy can
            find a real description/keywords/image/etc. that an earlier
            one missed, and that shouldn't be silently discarded just
            because the earlier strategy already supplied the core
            ingredients or instructions). If best is None, result becomes
            the new best outright (silently, matching the original
            single-strategy behavior); message is only printed when a
            later strategy actually supplements an existing partial
            result, not for the first strategy to find anything."""
            if best is None:
                print(message, file=sys.stderr)
                return dict(result)
            filled = False
            for field in MERGEABLE_FIELDS:
                if not best.get(field) and result.get(field):
                    best[field] = result[field]
                    filled = True
            if filled:
                print(message, file=sys.stderr)
            return best

        best = None

        result = app_state_scrape(html, url=args.url)
        if result:
            best = merge_in(best, result, "Found recipe data in an embedded JSON blob (e.g. __NEXT_DATA__).")
            if is_complete(best):
                return best

        result = wprm_scrape(html, url=args.url)
        if result:
            best = merge_in(best, result, "Found a WP Recipe Maker print page (no schema.org markup present).")
            if is_complete(best):
                return best

        result = jsonld_description_scrape(html, url=args.url)
        if result:
            best = merge_in(best, result, "Found recipe data embedded in a WebPage/Article JSON-LD description.")
            if is_complete(best):
                return best

        result = mntl_structured_content_scrape(html, url=args.url)
        if result:
            best = merge_in(best, result,
                             "Found a Dotdash Meredith style step-by-step how-to article (no ingredients "
                             "list on the page at all). Ingredients were inferred from '(I used ...)' "
                             "asides in the prose -- this list is very likely incomplete, so please "
                             "review and complete it by hand before using it.")
            if is_complete(best):
                return best

        result = step_heading_scrape(html, url=args.url)
        if result:
            best = merge_in(best, result,
                             "Found a series of 'Step N' headings with no list markup and no ingredients "
                             "list at all -- likely a how-to article for a pre-made/packaged food. Please "
                             "review the result before using it.")
            if is_complete(best):
                return best

        result = br_line_scrape(html, url=args.url)
        if result:
            best = merge_in(best, result,
                             "Found ingredients as bare <br>-separated lines with no real list markup "
                             "(and possibly instructions trapped inside broken/unclosed tags). This "
                             "page's markup is unusually malformed, so please double-check the result "
                             "before using it.")
            if is_complete(best):
                return best

        print("No embedded app-state recipe found; falling back to heuristic HTML parsing...",
              file=sys.stderr)

        def save_raw_html_on_failure():
            """When every parsing strategy has failed, save the raw,
            unparsed HTML to --out (if given) instead of leaving the user
            with nothing at all -- it's the starting point for building a
            custom parser for this page, the same raw material this
            script's author has needed for every site-specific fix so far."""
            if not args.out:
                return
            try:
                with open(args.out, "w", encoding="utf-8") as f:
                    f.write(html)
                print(f"Saved the raw, unparsed HTML to {args.out} for manual inspection.",
                      file=sys.stderr)
            except OSError as e_save:
                print(f"Could not save raw HTML to {args.out}: {e_save}", file=sys.stderr)

        try:
            result = heuristic_scrape(html, url=args.url)
            if result.get("recipeIngredient") or result.get("recipeInstructions"):
                if best is not None:
                    best = merge_in(best, result, "Found the missing piece via heuristic HTML parsing.")
                else:
                    best = result
            if not best:
                print("Heuristic parsing also failed to find ingredients/instructions. "
                      "This page's markup may need a custom parser.", file=sys.stderr)
                save_raw_html_on_failure()
                return None
            return best
        except Exception as e2:
            print(f"Heuristic fallback also failed: {e2}", file=sys.stderr)
            if best:
                return best
            save_raw_html_on_failure()
            return None

    if recipe_json is None:
        try:
            if args.file:
                recipe_json = scrape_from_file(args.file, url=args.url)
            else:
                recipe_json = scrape_from_url(args.url)
        except RecipeScrapersExceptions as e:
            print(f"recipe-scrapers found no schema markup ({e}); "
                  f"checking for an embedded app-state recipe blob...", file=sys.stderr)
            recipe_json = run_fallback_chain()
            if recipe_json is None:
                sys.exit(1)
        else:
            # recipe-scrapers can "succeed" by finding a validly-typed
            # Recipe schema that's nonetheless incomplete -- e.g. a source
            # site that uses a nonstandard "ingredients" key instead of
            # "recipeIngredient" (so it comes through empty) while
            # everything else (author, image, prepTime, category, ...) is
            # perfectly fine, or a stub object with the real recipe
            # content sitting in a sibling JSON-LD block instead. Rather
            # than discarding a partially-good result just because one
            # piece is missing, try the fallback strategies and merge in
            # only the specific field(s) that were actually empty --
            # keeping everything recipe-scrapers already got right.
            def _looks_like_unsplit_instructions(instructions):
                """
                Detects a source whose own JSON-LD lists every instruction
                step concatenated into ONE HowToStep -- often with
                numbered markers like "1. ... 2. ..." baked into the text
                itself, sometimes with literal "\\n" left over from a
                double-escaping bug on the source's end -- rather than a
                real array of separate steps. Technically non-empty, but
                not actually usable as separate steps, so this is checked
                for even when recipeInstructions has "some" content.
                """
                if not isinstance(instructions, list) or len(instructions) != 1:
                    return False
                text = instructions[0].get("text", "") if isinstance(instructions[0], dict) else ""
                if "\\n" in text:
                    return True
                return len(re.findall(r"(?:^|\s)\d{1,2}\.\s", text)) >= 3

            missing = []
            if not recipe_json.get("recipeIngredient"):
                missing.append("ingredients")
            if not recipe_json.get("recipeInstructions") or _looks_like_unsplit_instructions(
                recipe_json.get("recipeInstructions")
            ):
                missing.append("instructions")
            if missing:
                print(f"recipe-scrapers found a Recipe schema missing {' and '.join(missing)}; "
                      f"checking other strategies for just {'that' if len(missing) == 1 else 'those'}...",
                      file=sys.stderr)
                fallback_result = run_fallback_chain()
                if fallback_result:
                    if "ingredients" in missing and fallback_result.get("recipeIngredient"):
                        recipe_json["recipeIngredient"] = fallback_result["recipeIngredient"]
                        print("Found a usable ingredients list via a fallback strategy; using that.",
                              file=sys.stderr)
                    if "instructions" in missing and fallback_result.get("recipeInstructions"):
                        recipe_json["recipeInstructions"] = fallback_result["recipeInstructions"]
                        print("Found usable instructions via a fallback strategy; using those.",
                              file=sys.stderr)
                if not recipe_json.get("recipeIngredient") and not recipe_json.get("recipeInstructions"):
                    print("No usable ingredients or instructions could be found by any strategy.",
                          file=sys.stderr)
                    sys.exit(1)

    # An explicitly passed --url always takes precedence over whatever URL
    # ended up in recipe_json, whether that came from scraping a fresh page
    # or from reusing an existing JSON-LD file.
    if args.url:
        recipe_json["url"] = args.url

    if "name" in recipe_json:
        recipe_json["name"] = strip_redundant_title_suffix(recipe_json["name"])

    # Only normalize fields that actually contain human-written recipe text
    # with quantities in it -- not the whole dict. Running these regexes
    # over every string value (URLs, author names, keywords, category,
    # nutrition, ...) is dangerous: those fields can contain incidental
    # digit+letter sequences that accidentally look like a unit or a
    # temperature. In particular, a percent-encoded '/' in an image URL is
    # literally "%2F" -- indistinguishable by these regexes from "2°F" --
    # so a blanket deep-apply here was silently corrupting image URLs by
    # "converting" that encoded slash into a bogus temperature reading.
    for field in ("recipeIngredient", "recipeInstructions", "description"):
        if field in recipe_json:
            recipe_json[field] = normalize_fractions_deep(recipe_json[field])
            recipe_json[field] = normalize_measurements_deep(recipe_json[field])
    if "recipeIngredient" in recipe_json:
        recipe_json["recipeIngredient"] = normalize_ingredient_phrasing(recipe_json["recipeIngredient"])
        recipe_json["recipeIngredient"] = ensure_leading_quantity(recipe_json["recipeIngredient"])

    if args.nextcloud_url:
        upload_to_nextcloud(recipe_json, args.nextcloud_url, args.nextcloud_user, args.nextcloud_pass)
    else:

        output = (
            f'<!-- generated by recipe_to_jsonld.py -->\n'
            f'<script type="application/ld+json">\n{json.dumps(recipe_json, indent=2, ensure_ascii=False)}\n</script>'
        )

        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(output)
            print(f"Written to {args.out}")
        else:
            print(output)


if __name__ == "__main__":
    main()
