Bloomberg Security Search
=======================

This is a wrapper around the Java implementation of the Bloomberg API (http://www.bloomberglabs.com/api/about/).
This wrapper acts as a server and is able to receive multiple connections from multiple client implementations.
This is only a prototype and is not in anyway finished

```
JSON Object accepted:
{
    'request':
    {
        'yk_filter': "",
        'query_string': "test",
        'max_results': 10
    }
}
```
yk_filter is optional, leaving it empty means ALL, the other options are YK_FILTER_CRNY, YK_FILTER_EQTY, YK_FILTER_GOVT //TODO add the remaining ones (see Bloomberg API developer guide)

query_string is optional, and is the string used to search for.

max_results is optional, if not provided it defaults to 10.

```
JSON Objects returned:
{
    'error': "error msg"
}

{
    'response':
    [
        {
            security: "TEST",
            description: "Description for security TEST"
        },
        ...
    ]
}
```
Objects returned can be an error or a response which contains an array with objects, that contain the security name and description.

Libraries used:

`Bloomberg API Java`: http://www.bloomberglabs.com/api/libraries/

`JSON Simple`: https://code.google.com/p/json-simple/
