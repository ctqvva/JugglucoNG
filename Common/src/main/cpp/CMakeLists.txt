
if(${CMAKE_BUILD_TYPE})
else()
option(DEBUG "compile for debugging" OFF)
if(DEBUG)
set(CMAKE_CXX_FLAGS "-DDEBUG=1 -DSCANLOG=1 -UNDEBUG  -DNDK_DEBUG=1 " )
set(CMAKE_C_FLAGS "-DDEBUG=1 -DSCANLOG=1 -UNDEBUG  -DNDK_DEBUG=1  " )
if(JUGGLUCO_APP)
set(CMAKE_CXX_FLAGS " ${CMAKE_CXX_FLAGS} -fsanitize=address,undefined " )
set(CMAKE_C_FLAGS "$ ${CMAKE_C_FLAGS} -fsanitize=address,undefined " )
endif()
set(CMAKE_BUILD_TYPE Debug)
else()
if(LOG)
else()
   # Trace logging is now available in release builds (controlled at runtime via dolog flag)
   # When disabled, overhead is just one boolean check per log call
   set(LOG 1)
endif()
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -DRELEASE=1 -DNORAWSTREAM=1 -DRELEASEID=1 -DRELEASEDIR=1 ")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DRELEASE=1 -DNORAWSTREAM=1 -DRELEASEID=1 -DRELEASEDIR=1 ")
set(CMAKE_BUILD_TYPE Release)
endif()
endif()
#cmake_minimum_required(VERSION 3.4.1)
cmake_minimum_required(VERSION 3.13)
project(Glucodata)
set(MYCFLAGS " -g -Wall -Wno-sign-compare -fvisibility=hidden ")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS}  ${MYCFLAGS}" )
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}   ${MYCFLAGS} -Wno-vla-cxx-extension -fno-rtti -fexceptions  -DALWAYSPATH=1  ")

if(JUGGLUCO_APP)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}   -std=gnu++2b   ")
else()
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}  -std=gnu++2b ")
endif()

include(CheckIncludeFileCXX)
CHECK_INCLUDE_FILE_CXX(sys/prctl.h HAVE_PRCTL)

if(HAVE_PRCTL)
else()
set(CMAKE_CXX_FLAGS "${CMAKE_C_FLAGS} -DHAVE_NOPRCTL=1 ")
endif()
CHECK_INCLUDE_FILE_CXX(arm-linux-androideabi/asm/errno.h IsAndroid)
include(CheckCXXCompilerFlag)
CHECK_CXX_COMPILER_FLAG(-pthread HAVE_PTHREADARG)

set(INCLUDESELF ${CMAKE_CURRENT_SOURCE_DIR})
set(OPENGL_LIB GLESv3)
#set(OPENGL_LIB GLESv2)
set(CURVEDIR curve)
set(NETDIR net)
set(log-lib "log")
set(NANOVG ${CURVEDIR}/nanovg/src)
set(LIBASCON ${INCLUDESELF}/LibAscon)
set(APP_STL c++_static)
#set(APP_STL  c++_shared)
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
set(BCRYPTDIR ${INCLUDESELF}/bcrypt/)
set(BCRYPTFILES ${BCRYPTDIR}aes_encrypt.c ${BCRYPTDIR}bcrypt.cpp ${BCRYPTDIR}ccm_mode.c ${BCRYPTDIR}utils.c)

set(EXTRAVIEW ${NETDIR}/libreview/putsensor.cpp ${NETDIR}/libreview/mkrealhistory.cpp ${NETDIR}/libreview/libreview.cpp ${NETDIR}/libreview/newlibre3.cpp ${NETDIR}/libreview/journalentries.cpp ${NETDIR}/libreview/libreview.cpp )

set(LIBRE3FILES ${INCLUDESELF}/libre3/bluetooth.cpp ${INCLUDESELF}/libre3/nfc.cpp)

option(EXPERIMENTAL_LIBRE3_SOURCE "Build the unverified Libre 3 source security engine" OFF)
if(EXPERIMENTAL_LIBRE3_SOURCE)
set(LIBRE3SRC
    ${INCLUDESELF}/libre3/source_security_jni.cpp
    ${INCLUDESELF}/libre3/process/openssl_symbols.c
    ${INCLUDESELF}/libre3/process/libre3_handshake.c
    ${INCLUDESELF}/libre3/process/libre3_security_engine.c
    ${INCLUDESELF}/libre3/process/saved_authorization_record.c
    ${INCLUDESELF}/libre3/process/sensor_security_context.c
    ${INCLUDESELF}/libre3/process/p256_private_scalar_codec.c
    ${INCLUDESELF}/libre3/process/app_private_key_record_decoder.c
    ${INCLUDESELF}/libre3/process/app_private_key_whitebox_machine.c
    ${INCLUDESELF}/libre3/process/app_private_key_instruction_decoder.c
    ${INCLUDESELF}/libre3/process/app_private_key_instruction_executor.c
    ${INCLUDESELF}/libre3/process/app_private_key_instruction_set.c
    ${INCLUDESELF}/libre3/process/app_private_key_decode_schedule.c
    ${INCLUDESELF}/libre3/process/authorization_scalar_state_encoder.c
    ${INCLUDESELF}/libre3/process/p256_scalar_multiply.c
    ${INCLUDESELF}/libre3/process/authorization_round_feedback.c
    ${INCLUDESELF}/libre3/process/authorization_round_tail.c
    ${INCLUDESELF}/libre3/process/authorization_round_mixer.c
    ${INCLUDESELF}/libre3/process/authorization_round_output.c
    ${INCLUDESELF}/libre3/process/p256_coordinate_codec.c
    ${INCLUDESELF}/libre3/process/p256_coordinate_reduction.c
    ${INCLUDESELF}/libre3/process/authorization_round_primary.c
    ${INCLUDESELF}/libre3/process/authorization_round_secondary.c
    ${INCLUDESELF}/libre3/process/authorization_vector_reduction.c
    ${INCLUDESELF}/libre3/process/authorization_vector_feedback.c
    ${INCLUDESELF}/libre3/process/authorization_round_driver.c
    ${INCLUDESELF}/libre3/process/challenge_whitebox_block_cipher.cpp
    ${INCLUDESELF}/libre3/process/openssl_loader.cpp
    ${INCLUDESELF}/libre3/process/authorization_shared_point_frames.c
    ${INCLUDESELF}/libre3/process/authorization_shared_point_tail.c
    ${INCLUDESELF}/libre3/process/authorization_frame_transform.c
    ${INCLUDESELF}/libre3/process/authorization_digest.c
    ${INCLUDESELF}/libre3/process/authorization_finalizer.c
    ${INCLUDESELF}/libre3/process/p256_coordinate_extract.c
    ${INCLUDESELF}/libre3/process/p256_private_scalar_decoder.c
    ${INCLUDESELF}/libre3/process/p256_ephemeral_keypair.c
    ${INCLUDESELF}/libre3/process/challenge_cipher_context.c
)
endif()

set(ASCONFILES ${LIBASCON}/src/ascon_permutations.c ${LIBASCON}/src/ascon_buffering.c ${LIBASCON}/src/ascon_aead_common.c ${LIBASCON}/src/ascon_aead128a.c)
add_definitions(-DANDROID_STL=c++_static -DDYNLINK -DUSE_MEAL -DNOTIMEOUT  )
add_library( g SHARED ${LIBRE3SRC} ${NETDIR}/ICE/ContextHTTPS.cpp ${NETDIR}/ICE/ICE.cpp ${NETDIR}/ICE/ICE_data.cpp ICEstatus.cpp ${BCRYPTFILES} meter/java.cpp calibrate/java.cpp  calibrate/calculate.cpp  aidex/java.cpp  accu/java.cpp  dexcom/java.cpp dexcom/ecJPake.cpp extra/bypassed_dlfcn.c extra/fake_dlfcn.c dofirst.cpp settings/exception.cpp prunestream.cpp ${EXTRAVIEW} watch/messages.cpp watch/netinfo.cpp watch/watchvalue.cpp removeoldstates.cpp  ${CURVEDIR}/trjugglucotext.cpp   ${CURVEDIR}/svjugglucotext.cpp    ${CURVEDIR}/zhjugglucotext.cpp  ${CURVEDIR}/ukjugglucotext.cpp  ${CURVEDIR}/bejugglucotext.cpp ${CURVEDIR}/frjugglucotext.cpp ${CURVEDIR}/pljugglucotext.cpp ${CURVEDIR}/ptjugglucotext.cpp ${CURVEDIR}/esjugglucotext.cpp ${CURVEDIR}/nljugglucotext.cpp ${CURVEDIR}/dejugglucotext.cpp  ${CURVEDIR}/jugglucotext.cpp  ${CURVEDIR}/itjugglucotext.cpp ${CURVEDIR}/percentile.cpp foods/javabase.cpp ${ASCONFILES} hostJson.cpp backupjava.cpp glucose.cpp ${CURVEDIR}/shownums.cpp g.cpp libre2.cpp jnisub.cpp share/getsockets.cpp share/hooks.cpp wearInt.cpp logjava.cpp share/logs.cpp share/nfcdata.cpp  share/serial.cpp scanstate.cpp statedata.cpp share/timestr.cpp share/savehistory.cpp ${NANOVG}/nanovg.c  ${CURVEDIR}/search.cpp ${CURVEDIR}/appcurve.cpp ${CURVEDIR}/curve.cpp ${CURVEDIR}/javacurve.cpp export/libreviewexport.cpp export/exports.cpp settings/javasettings.cpp settings/settings.cpp debugclone.cpp ${NETDIR}/watchserver/common.cpp ${NETDIR}/watchserver/uploader.cpp  ${NETDIR}/watchserver/uploadtreatment.cpp  ${NETDIR}/watchserver/iob.cpp   ${NETDIR}/watchserver/sslserver.cpp  ${NETDIR}/watchserver/cryptopen.cpp ${NETDIR}/watchserver/watchserver.cpp ${NETDIR}/netstuff.cpp ${NETDIR}/sender.cpp ${NETDIR}/sendcommands.cpp ${NETDIR}/backup.cpp ${NETDIR}/getcommand.cpp mirrorstatus.cpp backupinfo.cpp datbackup.cpp nums/numdata.cpp nums/numjava.cpp  meal/mealjava.cpp meal/savemeals.cpp  ${LIBRE3FILES} libre3/loadlibs.cpp ${CURVEDIR}/iwjugglucotext.cpp ${CURVEDIR}/rujugglucotext.cpp sibionics/eu.cpp sibionics/start.cpp sibionics/process.cpp sibionics/makeWriteCharacter.cpp  ) #  ${CURVEDIR}/iwjugglucotext.cpp
target_compile_definitions(g PRIVATE  -DUSE_SSL=1 -DANDROID__APP=1  -DJUGGLUCO_APP=1)
if(EXPERIMENTAL_LIBRE3_SOURCE)
target_compile_definitions(g PRIVATE
    EXPERIMENTAL_LIBRE3_SOURCE=1
    L3_PROVIDER_SOFT_EMBED_FE_BLOCK=1
    L3_USE_CRYPTO_DLSYM=1
)
endif()
target_include_directories(g PRIVATE ${NANOVG}  ${INCLUDESELF} ${INCLUDESELF}/share ${INCLUDESELF}/LibAscon/inc ${INCLUDESELF}/include ${INCLUDESELF}/bcrypt)
target_compile_definitions( g  PRIVATE  -DWEAROS_MESSAGES=1 -DDONTSCALESTROKEWIDTH  -DLIBRENUMBERS=1 )

add_link_options(-g  )
target_link_options(g PRIVATE "-Wl,-z,max-page-size=16384")
#target_link_options(${CMAKE_PROJECT_NAME} PRIVATE "-Wl,-z,max-page-size=16384")





set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared libraries" FORCE)
set(NO_SERVER ON CACHE BOOL "Disable server support" FORCE)
set(NO_TESTS  ON CACHE BOOL "Disable tests build" FORCE)
add_subdirectory(libjuice)

target_link_libraries( g PRIVATE c++_static c ${log-lib} ${OPENGL_LIB} android EGL m atomic z dl $<BUILD_INTERFACE:juice>)
export(TARGETS g ANDROID_MK Android.mk)


add_executable(libnative.so    abbotttest.cpp )
target_include_directories( libnative.so PRIVATE  ${INCLUDESELF} ${INCLUDESELF}/share  LibAscon/inc)
target_link_libraries( libnative.so    ${log-lib} ${OPENGL_LIB} android EGL m g )
target_compile_definitions(libnative.so PUBLIC -DANDROID__APP )


add_library(native SHARED IMPORTED)

if(JUGGLUCO_APP)
else()
option(static "compile static" OFF)
add_executable(juggluco calibrate/calculate.cpp ${ASCONFILES} hostJson.cpp backupinfo.cpp export/libreviewexport.cpp  ${NETDIR}/watchserver/common.cpp  ${NETDIR}/watchserver/iob.cpp    ${NETDIR}/watchserver/watchserver.cpp  ${NETDIR}/watchserver/sslserver.cpp ${NETDIR}/watchserver/cryptopen.cpp   removeoldstates.cpp nums/numdata.cpp settings/settings.cpp  ${INCLUDESELF}/cmdline/qr.cpp  cmdline/main.cpp share/logs.cpp  ${NETDIR}/netstuff.cpp  ${NETDIR}/sender.cpp ${NETDIR}/sendcommands.cpp ${NETDIR}/backup.cpp ${NETDIR}/getcommand.cpp ${NETDIR}/myip.cpp  datbackup.cpp glucose.cpp ${CURVEDIR}/trjugglucotext.cpp   ${CURVEDIR}/svjugglucotext.cpp    ${CURVEDIR}/zhjugglucotext.cpp  ${CURVEDIR}/ukjugglucotext.cpp  ${CURVEDIR}/bejugglucotext.cpp ${CURVEDIR}/frjugglucotext.cpp ${CURVEDIR}/pljugglucotext.cpp ${CURVEDIR}/ptjugglucotext.cpp ${CURVEDIR}/esjugglucotext.cpp ${CURVEDIR}/nljugglucotext.cpp ${CURVEDIR}/dejugglucotext.cpp  ${CURVEDIR}/jugglucotext.cpp  ${CURVEDIR}/itjugglucotext.cpp ${CURVEDIR}/rujugglucotext.cpp ${CURVEDIR}/curve.cpp ${CURVEDIR}/percentile.cpp ${NANOVG}/nanovg.c export/exports.cpp settings/getcountry.cpp  meal/savemeals.cpp share/serial.cpp prunestream.cpp ${EXTRACMD}  )
target_include_directories( juggluco PRIVATE ${INCLUDESELF} ${INCLUDESELF}/cmdline ${INCLUDESELF}/share  ${INCLUDESELF}/LibAscon/inc ${NANOVG}  )
#target_compile_options(juggluco PUBLIC  -static  -pthread )
add_executable(readlog readlog.cpp)
target_include_directories( readlog PRIVATE  ${INCLUDESELF} ${INCLUDESELF}/share  )

if(HAVE_PTHREADARG)
target_compile_options(juggluco PUBLIC $<$<COMPILE_LANGUAGE:CXX>:-pthread>)
endif()
if(static)
target_link_libraries( juggluco     m crypto ssl z juice)
target_link_options(juggluco  PUBLIC -static  -Wl,--whole-archive -lpthread  -Wl,--no-whole-archive )
target_compile_options(readlog PUBLIC  -fopenmp -static)
target_link_options(readlog  PUBLIC   -fopenmp -static )
set(extradefs -DUSE_SSL=1 )
else()
message(" CMAKE_SYSTEM_NAME =${CMAKE_SYSTEM_NAME} ")
#if( IsAndroid || ${CMAKE_SYSTEM_NAME} MATCHES "Android")
if(IsAndroid)
target_link_libraries( juggluco     m  dl z juice crypto ssl )
target_link_options(juggluco PUBLIC  -static-libstdc++)
message("is Android no pthread ")
set(extradefs -DUSE_SSL=1 -DANDROID_STL=c++_static )
else()
target_link_libraries( juggluco     m pthread dl z crypto ssl juice)
message(" pthread ")
set(extradefs -DUSE_SSL=1 )
endif()
target_compile_options(readlog PUBLIC  -fopenmp )
target_link_options(readlog  PUBLIC   -fopenmp  )

endif()

target_include_directories(juggluco PRIVATE ${INCLUDESELF} ${INCLUDESELF}/cmdline ${INCLUDESELF}/share ${INCLUDESELF}/include )
set(jugglucodef -DNOJAVA -DNOTAPP -DDONTSCALESTROKEWIDTH -DDONT_USE_LIBREVIEW=1 -DLIBRE3=1 -DDEXCOM=1 -DSIBIONICS=1 )

target_compile_definitions( juggluco  PRIVATE  ${jugglucodef} ${extradefs})
#-D_GLIBCXX_GTHREAD_USE_WEAK=0
get_target_property(sourjug juggluco SOURCE)
message(STATUS  ${sourjug})
set(NETPATH ${INCLUDESELF}/${NETDIR})
set(CURVEPATH ${INCLUDESELF}/${CURVEDIR})
set(depfiles ${INCLUDESELF}/calibrate/calculate.cpp ${INCLUDESELF}/hostJson.cpp ${INCLUDESELF}/export/libreviewexport.cpp ${INCLUDESELF}/curve/trjugglucotext.cpp   ${INCLUDESELF}/curve/svjugglucotext.cpp    ${INCLUDESELF}/curve/zhjugglucotext.cpp  ${INCLUDESELF}/curve/ukjugglucotext.cpp  ${INCLUDESELF}/curve/bejugglucotext.cpp ${INCLUDESELF}/curve/frjugglucotext.cpp ${INCLUDESELF}/curve/pljugglucotext.cpp ${INCLUDESELF}/curve/ptjugglucotext.cpp ${INCLUDESELF}/curve/esjugglucotext.cpp ${INCLUDESELF}/curve/nljugglucotext.cpp ${INCLUDESELF}/curve/dejugglucotext.cpp  ${INCLUDESELF}/curve/jugglucotext.cpp  ${INCLUDESELF}/curve/itjugglucotext.cpp ${INCLUDESELF}/curve/rujugglucotext.cpp ${INCLUDESELF}/curve/curve.cpp ${INCLUDESELF}/curve/percentile.cpp ${INCLUDESELF}/curve/nanovg/src/nanovg.c ${INCLUDESELF}/backupinfo.cpp ${INCLUDESELF}/prunestream.cpp ${INCLUDESELF}/readlog.cpp ${ASCONFILES} ${INCLUDESELF}/removeoldstates.cpp  ${INCLUDESELF}/nums/numdata.cpp ${INCLUDESELF}/settings/settings.cpp  ${INCLUDESELF}/cmdline/qr.cpp   ${INCLUDESELF}/cmdline/main.cpp ${INCLUDESELF}/share/logs.cpp ${NETPATH}/watchserver/common.cpp  ${NETPATH}/watchserver/watchserver.cpp ${NETPATH}/watchserver/iob.cpp ${NETPATH}/watchserver/cryptopen.cpp    ${NETPATH}/watchserver/sslserver.cpp  ${NETPATH}/netstuff.cpp  ${NETPATH}/sender.cpp ${NETPATH}/sendcommands.cpp ${NETPATH}/backup.cpp ${NETPATH}/getcommand.cpp ${NETPATH}/myip.cpp ${INCLUDESELF}/datbackup.cpp ${INCLUDESELF}/glucose.cpp ${INCLUDESELF}/export/exports.cpp ${INCLUDESELF}/settings/getcountry.cpp  ${INCLUDESELF}/meal/savemeals.cpp ${INCLUDESELF}/share/serial.cpp  )
#add_custom_target(juggluco-dep g++ -MF juggluco-dep -MM -I${INCLUDESELF}/${NANOVG} -I${INCLUDESELF} -I${INCLUDESELF}/share  -I${INCLUDESELF}/LibAscon/inc ${depfiles} DEPENDS ${depfiles}) 
add_custom_target(juggluco-dep /usr/bin/echo -n doel:>juggluco-dep\;g++  ${jugglucodef} -MM -I${INCLUDESELF}/cmdline -I${INCLUDESELF}/curve -I${INCLUDESELF}/${NANOVG} -I${INCLUDESELF} -I${INCLUDESELF}/share  -I${INCLUDESELF}/LibAscon/inc ${depfiles}|sed "\'s/^.*\.o:.*$$//g;s/\\([^\\]\\)$$/&\\\\/g'" | head -c-2 | grep -v '^$$' >> juggluco-dep\;echo "doel:" >> juggluco-dep\; /usr/bin/echo -e \"\\ttar -chf cmdlinesrc.tar  \"  $$^ ${depfiles} >> juggluco-dep DEPENDS ${depfiles}) 
add_custom_target(cmdlinesrc.tar make -f ${CMAKE_CURRENT_BINARY_DIR}/juggluco-dep doel DEPENDS juggluco-dep)
endif()
