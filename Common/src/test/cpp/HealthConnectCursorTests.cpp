#include "healthconnectcursor.hpp"
#include <cassert>
#include <thread>

int main() {
  uint16_t cursor = 100;
  auto token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(healthconnect::advance(&cursor, token, 600));
  assert(cursor == 600);
  // Latest PR finding: a rewind before the successful insert acknowledgement.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  std::thread writer([&] { healthconnect::gapFilled(&cursor, 50); });
  writer.join();
  assert(!healthconnect::advance(&cursor, token, 1000));
  assert(cursor == 50);
  // A gap INSIDE the chunk need not rewind its start; still invalidate it.
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::gapFilled(&cursor, 200);
  assert(cursor == 50);
  assert(!healthconnect::advance(&cursor, token, 550));
  token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(healthconnect::advance(&cursor, token, 550));
  assert(!healthconnect::advance(&cursor, token, 600));
  // A reset invalidates a same-position snapshot as well (the ABA case).
  token = healthconnect::snapshot(&cursor, 10, 1000);
  healthconnect::reset(&cursor, 550);
  assert(!healthconnect::advance(&cursor, token, 1000));
  token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(!healthconnect::advance(&cursor, token, 1001));
  assert(healthconnect::advance(&cursor, token, 1000));
  healthconnect::reset(&cursor, 0);
  token = healthconnect::snapshot(&cursor, 10, 1000);
  assert(uint16_t(token) == 10);
  assert(healthconnect::advance(&cursor, token, 510));
}
