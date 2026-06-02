package state

import (
	"github.com/DubbaThony/share-server/ifaces"
	"github.com/DubbaThony/share-server/util"
)

// "dont keep static values" yada, yada, ya. This is plainly too practical and thread safe.

var ConnMap = util.NewMuMap[uint64, ifaces.Connection]()
