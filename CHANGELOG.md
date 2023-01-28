Changelog
=========

v2.2.0
------
* Added a feature to stitch a local stack trace when reading throwables.
* Added a feature to pass an uncaught exception directly to the handler.
* Added a feature to transfer bytes from a pipe to an OutputStream.

v2.1.0 (2023-01-06)
------
* Fixed a bug when reading arrays of length zero when the input stream has no available bytes.
* Fixed handling of remote methods which have custom remote exceptions when restoring sessions.
* Added a feature to observe all accepted sockets.

v2.0.0 (2022-12-09)
------
* Version 2 is a complete rewrite.
