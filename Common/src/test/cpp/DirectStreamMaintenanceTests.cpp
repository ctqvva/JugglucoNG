#define NOLOGS_H
#include "inout.hpp"
#include "directstreammaintenance.hpp"

#include <cassert>
#include <cstdint>
#include <cstdlib>
#include <filesystem>

struct TestPoll {
  uint32_t timestamp;
  int value;
};

int main() {
  char path[] = "/tmp/juggluco-direct-stream-XXXXXX";
  const char *dir = mkdtemp(path);
  assert(dir);

  {
    Mmap<TestPoll> polls(dir, "polls.dat", 15 * 24 * 60);
    Mmap<uint16_t> raw(dir, "rawpolls.dat", 15 * 24 * 60);
    Mmap<uint16_t> temperatures(dir, "temppolls.dat", 15 * 24 * 60);
    polls.data()[120] = {1234u, 99};
    raw.data()[120] = 77;
    temperatures.data()[120] = 88;

    assert(directstream::ensurePollStorageCapacity(
        polls, raw, temperatures, dir, 30 * 24 * 60));
    assert(polls.size() >= 30 * 24 * 60);
    assert(raw.size() >= 30 * 24 * 60);
    assert(temperatures.size() >= 30 * 24 * 60);
    assert(polls.data()[120].timestamp == 1234u);
    assert(polls.data()[120].value == 99);
    assert(raw.data()[120] == 77);
    assert(temperatures.data()[120] == 88);
  }

  {
    Mmap<TestPoll> polls(dir, "polls.dat", 30 * 24 * 60);
    assert(polls.size() >= 30 * 24 * 60);
    assert(polls.data()[120].value == 99);
  }

  assert(directstream::nightSensorAfterRewind(7, 3) == 3);
  assert(directstream::nightSensorAfterRewind(2, 3) == 2);
  assert(directstream::nightSensorAfterRewind(0, 3) == 0);

  std::filesystem::remove_all(dir);
  return 0;
}
