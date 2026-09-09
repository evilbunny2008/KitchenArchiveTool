# Kitchen Archive Tool (KAT)

This was forked from https://codeberg.org/MicMun/nextcloud-cookbook

## About

This app is a viewer for recipes in Nextcloud Cookbook server app.
You need the Nextcloud Android client app to sync the recipes.

**First steps**

First view after installation is a login screen. Tap the login button to choose a Nextcloud account from the
Nextcloud client app and sync directly with your Nextcloud server — this is the only way into the app, since
its whole purpose is working against a Nextcloud Cookbook instance.

You also can choose the theme in the settings.

## Changes since the fork

### New features

- **Copy a recipe to another account.** Long-press a recipe on the list to copy it — including its photo — to
  any other account signed in to the app. The photo is carried over by staging it in the destination account's
  own Nextcloud storage first, since the Cookbook server has no way to accept an uploaded image directly.
- **Delete a recipe.** Also from the long-press menu, with a confirmation step first. Deletes from the server,
  then removes the local copy — never the other way around, so a failed server-side delete can't leave the
  local copy gone while the server still has it.
- **Import a recipe from a URL.** Paste in a link from (almost) any recipe site and it's added straight to
  Cookbook — most sites don't embed the structured data Cookbook itself can read directly, so this sends the
  URL to a small companion service that scrapes the page and returns it in the right format, then the app
  uploads it using the account you're already signed in with. That service never sees, stores, or needs any
  Nextcloud credential — its only job is turning a URL into recipe data; uploading it is entirely the app's
  own doing.
- **New app icon and branding**, including a themed/monochrome adaptive icon variant.

### Fixes

- **Background/scheduled sync no longer needs a foreground service.** Replaced the old foreground `Service` +
  `AlarmManager` combination with `WorkManager`, which doesn't need `FOREGROUND_SERVICE` permissions or a
  persistent notification, and respects battery-optimization windows automatically. This also fixed a
  pre-existing bug where scheduled background sync was silently never actually running.
- **"Date published" sort was effectively sorting alphabetically.** Most recipes don't have that (optional)
  field set, so nearly everything tied and fell back to insertion order. Sorting now falls back to the
  recipe's creation date, which the server always sets, when a publish date isn't available.
- **Recipe dates were being parsed incorrectly**, causing every sync to treat every recipe as changed and
  re-download it regardless of whether anything had actually changed.
- **Recipes from a previously-active account could silently reappear** after switching to a different account
  with no recipes of its own, caused by old, never-cancelled background queries continuing to overwrite the
  list in place.
- **The category filter menu was showing categories from every signed-in account**, not just the current one,
  for the same reason as above.
- **The account switcher's avatar/name could get stuck** showing the previous account after switching, because
  the switch's own background work was being cancelled partway through.
- **The copied-in recipe from another account wouldn't show up** until a manual pull-to-refresh; the recipe
  list now also syncs automatically when it becomes visible again, not just on an explicit refresh or account
  switch.
- **Fixed a couple of memory/resource leaks** in the sync code (an unclosed network connection opened on every
  sync, and a leak inside a third-party library's image-loading integration, worked around by fetching account
  avatars directly instead).
- Account switching and the copy-to-account picker now show the account list **immediately** instead of
  waiting on a network request per account, and cache avatars locally so they load instantly on repeat visits.
- **Icons and list content no longer render underneath the status bar or the gesture/navigation bar** on
  newer Android versions, including in the account/settings drawer.
- **Pressing back while filtering by a category now clears the filter first**, showing all recipes again,
  instead of exiting (or, briefly, behaving inconsistently depending on how you'd navigated there).
- The "Import recipe"/"Settings" entries in the account drawer were moved to the top and no longer sit under
  an unnecessary "App" heading.
- **A transient, recoverable server error could wipe out every locally-stored recipe.** If fetching the
  recipe list from the server failed (e.g. a temporary network hiccup, or the Nextcloud Files app's
  background connection not responding), that failure was being silently treated as "the server now has zero
  recipes" — and cleanup, which deletes any local recipe no longer present on the server, would then delete
  everything. A failed fetch now correctly reports the sync as failed instead, and local recipes are left
  completely untouched; the next scheduled sync or pull-to-refresh simply tries again.
- **The initial sync right after logging in didn't survive the screen being rotated.** It ran on a background
  thread tied directly to that one screen, so rotating recreated the screen from scratch (showing the login
  button again, as if nothing had happened) while the old sync kept running independently and invisibly. If
  you then went through the freshly-shown login screen again too, two syncs could end up writing to the same
  local files at once, which could leave the import incomplete in a way a later pull-to-refresh wouldn't fix.
  The initial sync is now handled the same durable way as every other sync in the app, survives the screen
  being recreated, and can't run twice at once.
- **The login screen could reappear on a later app launch even after already signing in successfully once**,
  caused by a related issue in the same area above.
- **The app could crash while a sync was still in progress.** Recipe thumbnails appear live as recipes are
  downloaded, so it was possible to try showing one before its image had actually finished downloading; that
  now falls back to no image temporarily instead of crashing, and picks up the real image next time the list
  refreshes.
- **A rare crash could happen when the recipe list refreshed from more than one place at the same time**
  (e.g. right after signing in), caused by two of those refreshes both trying to rebuild the same
  keyword/category data at once. Fixed by making that update atomic.
- **Content could render underneath the navigation bar in landscape**, where it commonly sits at a side edge
  rather than the bottom.

## Dependencies

This app needs Android 10+ (minSdk is 29) and uses the libraries (see also app/build.gradle):

- androidx dependencies (including WorkManager for background sync)
- kotlinx coroutines
- kotlinx-serialization-json (JSON parser)
- [kpermissions by fondesa](https://github.com/fondesa/kpermissions) (permission handling)
- [SimpleStorage by anggrayudi](https://github.com/anggrayudi/SimpleStorage) (storage handling and choosing a directory)
- [Android-SingleSignOn by Nextcloud](https://github.com/nextcloud/Android-SingleSignOn) (single sign on with
  nextcloud client)

## License

**Copyright 2020-2024 by MicMun**

This program is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.
You should have received a copy of the GNU General Public License along with this program. If not, see
[http://www.gnu.org/licenses/](http://www.gnu.org/licenses/).
