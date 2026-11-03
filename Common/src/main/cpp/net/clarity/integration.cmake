# Dexcom Clarity uploader, ported from upstream Juggluco 11.5.6.
# The JNI surface exists in every flavour; when the uploader is not built its
# entry points report "not built" instead of failing to link.
target_sources(g PRIVATE "${CMAKE_CURRENT_LIST_DIR}/juggluco.cpp")
option(CLARITY "Enable the native Dexcom Clarity uploader" OFF)
if(CLARITY AND NOT WEAROS AND ANDROID_ABI MATCHES "^(arm64-v8a|armeabi-v7a)$")
    # Upstream's combined Libre/Clarity provider; restored by scripts/restore-build-inputs.sh.
    set(CLARITY_PROVIDER_LIBRARY "liblibre_receiver_skb.so")
    add_subdirectory("${CMAKE_CURRENT_LIST_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/clarity")
    target_compile_definitions(clarity_core PRIVATE CLARITY_JUGGLUCO_LOG=1)
    target_sources(g PRIVATE "${CMAKE_CURRENT_LIST_DIR}/transport.cpp")
    target_compile_definitions(g PRIVATE JUGGLUCO_CLARITY=1)
    target_link_libraries(g PRIVATE clarity_core)
endif()
