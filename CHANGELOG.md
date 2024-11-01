Changelog
=========

v2.4.1 (2024-11-01)
------
* The Pipe interface also implements ByteChannel now.

v2.4.0 (2024-10-10)
------
* Ignore static methods in remote interfaces.
* Added pipe methods for efficiently encoding and decoding complex objects.
* Added support for automatic remote object disposal.
* Added a session reconnect method.
* Optimize reading and writing primitive arrays.

v2.3.3 (2024-04-10)
------
* Fixed a bug when comparing the client and server remote interfaces. Remote methods which
  differed only by their annotations or exceptions would sometimes be treated as equal, leading
  to mismatched code generation on the client and server sides.

v2.3.2 (2023-07-30)
------
* More lenient restorable object race condition fixes.

v2.3.1 (2023-06-28)
------
* Fixed a race condition which prevented lenient restorable objects from being restored.

v2.3.0 (2023-03-04)
------
* Fixed a bug when a restored object has access to a method which wasn't originally implemented.
* Fixed a few race conditions during object restoration.
* Replaced the Session.stateListener method with addStateListener.
* Added a debug option to close pipes when they are recycled.
* Methods unimplemented on the server throw an exception only allowed by the RemoteFailure
  annotation. Before, UnimplementedException would be thrown always.
* RemoteException now includes the remote address.
* Added a lenient option for restorable methods.

v2.2.0 (2023-02-04)
------
* Relax the pipe recycling check to consider the case when the remote side has already sent the
  next request. This prevents a bogus IllegalStateException from being thrown.
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
