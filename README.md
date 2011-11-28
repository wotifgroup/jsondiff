NOTE
====

This is a port to Jackson from Gson of https://github.com/algesten/jsondiff

Structural JSON diff/patch tool
===============================

The patches are json themselves where the object memeber keys follows
a certain syntax.

Example:

    var orig = {
      a: 1,
      b: ["an", "array"],
      c: { "an": "object" }
    };
 
    var patch = {
      a: 42,                      // replace a
      "b[+1]": "example",         // insert into array b
      "~c": { "for": "example" }  // merge into object c (It's a TILDE)
    };

    var changed = patch(orig, patch);

would yield a changed object as:

    {
      a: 42,
      b: ["an", "example", "array"],
      c: {"an": "object", "for": "example" }
    }

### Syntax ###

* `~` (tilde) is an object merge. I.e. `{a:1}` merged with `{b:2}` would yield `{a:1,b:2}`
* `-` (hyphen-minus) is a removal.
* `[n]` is an array index i.e. `a[4]` means element 4 in array named `a`.
* `[+n]` means an array insertion. i.e `a[+4]` means insert an item at position 4.

### Further definitions ###

    "key":     "replaced",           // added or replacing key
    "~key":    "replaced",           // added or replacing key (~ doesn't matter for primitive data types)
    "key":     null,                 // added or replacing key with null.
    "~key":    null,                 // added or replacing key with null (~ doesn't matter for null)
    "-key":    0                     // key removed (value is ignored)
    "key":     { "sub": "replaced" } // whole object "key" replaced
    "~key":    { "sub": "merged" }   // key "sub" merged into object "key", rest of object untouched
    "key":     [ "replaced" ]        // whole array added/replaced
    "~key":    [ "replaced" ]        // whole array added/replaced (~ doesn't matter for whole array)
    "key[4]":  { "sub": "replaced" } // object replacing element 4, rest of array untouched
    "~key[4]": { "sub": "merged"}    // merging object at element 4, rest of array untouched
    "key[+4]": { "sub": "array add"} // object added after 3 becoming the new 4 (current 4 pushed right)
    "~key[+4]":{ "sub": "array add"} // object added after 3 becoming the new 4 (current 4 pushed right)
    "-key[4]:  0                     // removing element 4 current 5 becoming new 4 (value is ignored)
