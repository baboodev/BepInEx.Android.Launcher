#pragma once

// Neutralize THEMIS F000000A self-exit (dual-mirror integrity → exit_group(1)).
// Safe to call more than once; no-op if themis is not loaded or already patched.
bool install_themis_f000000a_guard();
