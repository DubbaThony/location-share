package ifaces

import "errors"

var ErrPacketMalformed = errors.New("packet malformed")
var ErrNonExistentConnection = errors.New("non existent connection")
