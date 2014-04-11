<!-- This file is *generated* by #'clojure.tools.nrepl.describe-test/update-op-docs
   **Do not edit!** -->
# Supported nREPL operations

<small>generated from a verbose 'describe' response (nREPL v0.2.4-SNAPSHOT)</small>

## Operations

### `:clone`

Clones the current session, returning the ID of the newly-created session.

###### Required parameters



###### Optional parameters

* `:session` The ID of the session to be cloned; if not provided, a new session with default bindings is created, and mapped to the returned session ID.


###### Returns

* `:new-session` The ID of the new session.


### `:close`

Closes the specified session.

###### Required parameters

* `:session` The ID of the session to be closed.


###### Optional parameters



###### Returns



### `:describe`

Produce a machine- and human-readable directory and documentation for the operations supported by an nREPL endpoint.

###### Required parameters



###### Optional parameters

* `:verbose?` Include informational detail for each "op"eration in the return message.


###### Returns

* `:ops` Map of "op"erations supported by this nREPL endpoint
* `:versions` Map containing version maps (like \*clojure-version\*, e.g. major, minor, incremental, and qualifier keys) for values, component names as keys. Common keys include "nrepl" and "clojure".


### `:eval`

Evaluates code.

###### Required parameters

* `:code` The code to be evaluated.
* `:session` The ID of the session within which to evaluate the code.


###### Optional parameters

* `:id` An opaque message ID that will be included in responses related to the evaluation, and which may be used to restrict the scope of a later "interrupt" operation.
* `:eval` A namespace qualified symbol denoting an alternative to the `clojure.core/eval` function.


###### Returns



### `:interrupt`

Attempts to interrupt some code evaluation.

###### Required parameters

* `:session` The ID of the session used to start the evaluation to be interrupted.


###### Optional parameters

* `:interrupt-id` The opaque message ID sent with the original "eval" request.


###### Returns

* `:status` 'interrupted' if an evaluation was identified and interruption will be attempted
'session-idle' if the session is not currently evaluating any code
'interrupt-id-mismatch' if the session is currently evaluating code sent using a different ID than specified by the "interrupt-id" value 


### `:load-file`

Loads a body of code, using supplied path and filename info to set source file and line number metadata. Delegates to underlying "eval" middleware/handler.

###### Required parameters

* `:file` Full contents of a file of code.


###### Optional parameters

* `:file-name` Name of source file, e.g. io.clj
* `:file-path` Source-path-relative path of the source file, e.g. clojure/java/io.clj


###### Returns



### `:ls-sessions`

Lists the IDs of all active sessions.

###### Required parameters



###### Optional parameters



###### Returns

* `:sessions` A list of all available session IDs.


### `:stdin`

Add content from the value of "stdin" to \*in\* in the current session.

###### Required parameters

* `:stdin` Content to add to \*in\*.


###### Optional parameters



###### Returns

* `:status` A status of "need-input" will be sent if a session's \*in\* requires content in order to satisfy an attempted read operation.
