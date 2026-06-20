# Overview

### TLDR

Allow client to adjust preferred server timeouts.

### more specifically

Add feature flag, that allows client to manage the timeout, within
configured min/max values.
If server responds w/o this feature flag back to client, it's 
either "feature unsupported" or "feature disabled".

This allows new ctrlframe type `100`, named `TimeoutManagement` to
be sent by client, and server responds with smae ctrl type id.
Server responds in extra payload with uint8 enum of status.
Allowed statuses are "value too low" (`255`),
"value too high" (`254`)
"value identical with current" (`253`)
and
"OK"(`1`). Other values are treated as generic error and
breach of proto.

This change almost doesnt require a protocol version bump, since it's covered
by feature flag and generic parts of protocol.

Only protocol change it requires, is establising range of ctrl types
100 - 200 to be feature-flag specific values, to avoid other 
proto upgrade to use one of these values. Disregardable in code itself,
so there is no point of forcing increment of proto ver.
