APPDIR=$(cd "$(dirname "$0")"/../../../.. && pwd)
cd $APPDIR/Common/.cxx/cmake/debug/arm64-v8a
ninja&& adb -s RQ3006YK2M push $APPDIR/Common/build/intermediates/cmake/debug/obj/arm64-v8a/*.so /sdcard/libre/libs&& (adb -s RQ3006YK2M shell su -c sh -x /sdcard/libre/lcopy.sh)

