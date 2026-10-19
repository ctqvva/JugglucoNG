#pragma once

/*
 * Do not commit Twilio credentials. Local builds that need Twilio TURN token
 * refresh can provide Common/src/main/cpp/net/ICE/twilio.local.hpp with:
 *
 *   #define TWILIOACCOUNT "..."
 *   #define USERPASSBASE64 "..."
 */
