# Send readings to xDrip+ Sync Follower

This integration sends live glucose readings from JugglucoNG to xDrip+ on the
same Android phone. It uses xDrip's existing Nightscout client `NEW_SGV`
broadcast receiver, which accepts readings when **xDrip Sync Follower** is the
selected collection source. No xDrip source changes or cloud sync key are
needed.

1. In xDrip+, choose **xDrip Sync Follower** as the data source. In its advanced
   settings, keep **Accept glucose** (`accept_nsclient_sgv`) enabled.
2. In JugglucoNG, open **Settings → Exchange data** and enable
   **xDrip+ Sync Follower**.

JugglucoNG sends the current value, timestamp and trend when the exchange
output gate emits a reading. xDrip ignores duplicate timestamps. This is a
local phone integration; it does not join an xDrip cloud sync group or send
historical backfill.
