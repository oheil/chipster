##depends:none

# meme, quite free, see documentation                                    
	cd ${TMPDIR_PATH}
	curl http://ebi.edu.au/ftp/software/MEME/4.2.0/meme_4.2.0.tar.gz | tar -xz -C ${TMPDIR_PATH}
	cd meme_4.2.0/
	./configure --prefix=${TOOLS_PATH}/meme_4.2.0 --with-url="http://meme.nbcr.net/meme"
	make
	make install
	ln -s meme_4.2.0 ${TOOLS_PATH}/meme
	cd ..
	rm -rf ${TMPDIR_PATH}/meme_4.2.0
