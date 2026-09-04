# Kitchen Archive Tool (KAT)

This was forked from https://codeberg.org/MicMun/nextcloud-cookbook

## About

This app is a viewer for recipes in Nextcloud Cookbook server app.
You need the Nextcloud Android client app to sync the recipes.

**First steps**

First view after installation is a login screen with two ways to use the app.  
With the login button you can choose a nextcloud account from nextcloud client and 
sync directly with the nextcloud server.

If you want to use the local storage you choose "Skip for local storage" and go into settings to choose
the recipe directory for syncing.  
(E.g. the folder for the nextcloud client is _Android/media/com.nextcloud.client/nextcloud/&lt;your account&gt;/&lt;
folder&gt;_).

You also can choose the theme in the settings.

## Changes since the fork

### New features

- **Copy a recipe to another account.** Long-press a recipe on the list to copy it — including its photo — to
  any other account signed into the app. The photo is carried over by staging it in the destination account's
  own Nextcloud storage first, since the Cookbook server has no way to accept an uploaded image directly.
- **Delete a recipe.** Also from the long-press menu, with a confirmation step first. Deletes from the server,
  then removes the local copy — never the other way around, so a failed server-side delete can't leave the
  local copy gone while the server still has it.
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

## Dependencies

This app needs Android 10+ (minSdk is 29) and uses the libraries (see also app/build.gradle):

- androidx dependencies (including WorkManager for background sync)
- kotlinx coroutines
- kotlinx-serialization-json (json parser)
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
