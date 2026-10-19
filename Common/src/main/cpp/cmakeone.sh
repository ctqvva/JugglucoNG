APPDIR=$(cd "$(dirname "$0")"/../../../.. && pwd)
export OUTPUTDIR=$APPDIR/Common/build/mij/debug
sh cmakemake.sh
