# Nextcloud-Cookbook

This was forked from https://codeberg.org/MicMun/nextcloud-cookbook

## About

This app is a viewer for recipes in Nextcloud App.
You need the Nextcloud Android client to sync the recipes.

**First steps**

First view after installation is a login screen with two ways to use the app.  
With the login button you can choose a nextcloud account from nextcloud client and 
sync directly with the nextcloud server.

If you want to use the local storage you choose "Skip for local storage" and go into settings to choose
the recipe directory for syncing.  
(E.g. the folder for the nextcloud client is _Android/media/com.nextcloud.client/nextcloud/&lt;your account&gt;/&lt;
folder&gt;_).

You also can choose the theme in the settings.

## Dependencies

This app needs Android 10+ (minSdk is 29) and uses the libraries (see also app/build.gradle):

- androidx dependencies
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
