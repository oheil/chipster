##depends:none

## Create checksums
  #cd ${TOOLS_PATH}/
  
  #if [ $parallel == "1" ]; then
	#find . '!' -type d '!' -type l -print0 | parallel --gnu --no-notice -j $jobs -q0 --keep-order -X -N10 sha256sum >> tools.sha256sum
  #else
  	find . '!' -type d '!' -type l -print0 | xargs -0 sha256sum >> tools.sha256sum
#fi
