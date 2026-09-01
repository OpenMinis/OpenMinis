// Package gomobile exposes rclone to Android via gomobile.
//
// Mirrors rclone's own librclone/gomobile package, with ONE difference: that
// one imports `backend/all` (70 backends, ~26 MB gz). This imports the trimmed
// set from ../backends instead, so both platforms link exactly the same
// backends from a single list.
//
// Signatures follow gobind's type restrictions (string/int/error and pointers
// to structs) — see https://pkg.go.dev/golang.org/x/mobile/cmd/gobind.
package gomobile

import (
	_ "github.com/OpenMinis/minis-rclone/backends"
	"github.com/rclone/rclone/librclone/librclone"
)

// RcloneInitialize initialises the library. Call once before any RPC.
func RcloneInitialize() { librclone.Initialize() }

// RcloneFinalize releases resources. Call when finished.
func RcloneFinalize() { librclone.Finalize() }

// RcloneRPCResult is the gobind-compatible return shape.
type RcloneRPCResult struct {
	Output string
	Status int // HTTP-style; 200 = OK
}

// RcloneRPC runs one rclone RPC call, e.g.
// RcloneRPC("operations/list", `{"fs":"remote:","remote":""}`).
func RcloneRPC(method string, input string) *RcloneRPCResult {
	out, status := librclone.RPC(method, input)
	return &RcloneRPCResult{Output: out, Status: status}
}
