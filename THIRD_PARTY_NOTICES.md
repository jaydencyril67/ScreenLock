# Third-party notices

## Google Material Design Icons

The fingerprint and padlock vector artwork in:

- app/src/main/res/drawable/ic_s20_fingerprint.xml
- app/src/main/res/drawable/ic_phantom_padlock.xml

are derived from Google Material Design Icons.

Source repository: https://github.com/google/material-design-icons

License: Apache License, Version 2.0
Copyright: Google LLC

The icons are modified for Phantom Lock tinting and sizing. They are not official Samsung assets and are not affiliated with Samsung.

## One UI Design Library

The One UI-inspired components and theme resources used by Phantom's launcher are provided by:

- Project: https://github.com/tribalfs/oneui-design
- Copyright: Tribalfs; portions credited to Yanndroid and BlackMesa123
- License: MIT

## Samsung Experience Support Library (SESL) components

The One UI Design Library depends on modified AndroidX and Material components:

- SESL AndroidX: https://github.com/tribalfs/sesl-androidx
- SESL Material Components: https://github.com/tribalfs/sesl-material-components-android
- License: Apache License, Version 2.0

These are independent community projects and are not official Samsung software or affiliated with Samsung.


## One UI Home Clone launcher foundation

Phantom incorporates launcher components from **One UI Home Clone**:
- Source: https://github.com/SysAdminDoc/one-ui-home-clone
- Copyright: One UI Home Clone contributors
- License: MIT
- The original MIT license is included at `app/src/main/assets/one-ui-home-clone-LICENSE.txt`.

The imported implementation supplies the Compose-based Home surface, Apps screen/Finder, folders, widget host and picker, launcher settings, and related local persistence/recovery components. It is integrated into Phantom's existing Android application; Phantom retains its own application ID, settings entry point, lockscreen, and protection services.

One UI Home Clone is an independent community project. It is not Samsung software and is not affiliated with Samsung.
