#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace dp {

constexpr std::uint8_t bitrev8(std::uint8_t value) noexcept {
    value = static_cast<std::uint8_t>(((value & 0xF0u) >> 4) | ((value & 0x0Fu) << 4));
    value = static_cast<std::uint8_t>(((value & 0xCCu) >> 2) | ((value & 0x33u) << 2));
    return static_cast<std::uint8_t>(((value & 0xAAu) >> 1) | ((value & 0x55u) << 1));
}

[[nodiscard]] constexpr std::uint16_t
activation_crc(std::span<const std::byte> data) noexcept {
    std::uint16_t crc = 0xFFFFu;
    for (const std::byte value : data) {
        crc = static_cast<std::uint16_t>(
            crc ^ (std::uint16_t{bitrev8(std::to_integer<std::uint8_t>(value))} << 8));
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000u)
                ? static_cast<std::uint16_t>((crc << 1) ^ 0x1021u)
                : static_cast<std::uint16_t>(crc << 1);
        }
    }
    return crc;
}

[[nodiscard]] constexpr std::array<std::byte, 10>
activation_command_data(std::int64_t time, std::int64_t account) noexcept {
    std::array<std::byte, 10> result{};
    const std::uint32_t time_minus_one = static_cast<std::uint32_t>(time - 1);
    const std::uint32_t account_low = static_cast<std::uint32_t>(account);
    for (unsigned byte = 0; byte < 4; ++byte) {
        result[byte] = std::byte{static_cast<std::uint8_t>(time_minus_one >> (byte * 8))};
        result[byte + 4] = std::byte{static_cast<std::uint8_t>(account_low >> (byte * 8))};
    }
    const std::uint16_t crc = activation_crc(std::span<const std::byte>{result.data(), 8});
    result[8] = std::byte{static_cast<std::uint8_t>(crc)};
    result[9] = std::byte{static_cast<std::uint8_t>(crc >> 8)};
    return result;
}

constexpr auto kRecordedActivation = activation_command_data(0x6286428dLL, 0x1f416d8dLL);
static_assert(kRecordedActivation == std::array<std::byte, 10>{
    std::byte{0x8c}, std::byte{0x42}, std::byte{0x86}, std::byte{0x62},
    std::byte{0x8d}, std::byte{0x6d}, std::byte{0x41}, std::byte{0x1f},
    std::byte{0xbc}, std::byte{0x93},
});
static_assert(activation_command_data(1, 0) == std::array<std::byte, 10>{
    std::byte{0x00}, std::byte{0x00}, std::byte{0x00}, std::byte{0x00},
    std::byte{0x00}, std::byte{0x00}, std::byte{0x00}, std::byte{0x00},
    std::byte{0x3e}, std::byte{0x31},
});
static_assert(activation_command_data(9, 12) == std::array<std::byte, 10>{
    std::byte{0x08}, std::byte{0x00}, std::byte{0x00}, std::byte{0x00},
    std::byte{0x0c}, std::byte{0x00}, std::byte{0x00}, std::byte{0x00},
    std::byte{0x63}, std::byte{0x20},
});

} // namespace dp
