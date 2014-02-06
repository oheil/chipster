# TOOL prinseq-complexity-filter.R: "Filter reads for low complexity" (Filters out low complexity reads using either the DUST or ENTROPY method. The method is selected by defining a threshold value for one of the methods. This tool is based on the PRINSEQ package)
# INPUT fastqfile: "Input sequence set" TYPE GENERIC
# OUTPUT OPTIONAL accepted.fastq
# OUTPUT OPTIONAL accepted.fasta
# OUTPUT OPTIONAL rejected.fastq
# OUTPUT OPTIONAL rejected.fasta
# OUTPUT OPTIONAL filter.log
# PARAMETER OPTIONAL lc.dust: "DUST filter threshold" TYPE INTEGER (Use DUST method with the given maximum allowed score, between 0 and 100. Reads with complexity scores above 7 can be considered low complexity.)
# PARAMETER OPTIONAL lc.entropy: "ENTROPY filter threshold" TYPE INTEGER (Use ENTROPY method with the given minimum allowed entropy value, between 0 and 100. Reads with entropy value below 70 can be considered low complexity.)
# PARAMETER OPTIONAL output.mode: "Results to write out" TYPE [ filt: "accepted reads only", both: "accepted and rejected reads into separate files"] DEFAULT filt (With this section you can define if the reads that get filtered out are collected to a separate file.) 
# PARAMETER OPTIONAL input.mode: "Input file format" TYPE [ fq: "FASTQ", fa: "FASTA"] DEFAULT fq (Define the file format of the reads file.)
# PARAMETER OPTIONAL log.file: "Write a log file" TYPE [ n: "no", y: "yes"] DEFAULT y (Write a log file.)

# KM 17.1.2012

# check out if the file is compressed and if so unzip it
source(file.path(chipster.common.path, "zip-utils.R"))
unzipIfGZipFile("fastqfile")


# binary
binary.prinseq <- c(file.path(chipster.tools.path, "prinseq", "prinseq-lite.pl" ))

filter.params <- paste(" ")

if (!is.na(lc.dust)) {
	if (!is.na(lc.entropy)) {
	   stop(paste('CHIPSTER-NOTE: Please give threshold value only for one complexity filtering method, not both of them'))
	}
}

if (!is.na(lc.dust)) {
	filter.params <- paste(filter.params, "-lc_method dust -lc_threshold", lc.dust )
}

if (!is.na(lc.entropy)) {
	filter.params <- paste(filter.params, "-lc_method entropy -lc_threshold", lc.entropy )
}

if (output.mode == "both") {
	filter.params <- paste(filter.params, "-out_bad rejected")
}

if (input.mode == "fq") {
	filter.command <- paste(binary.prinseq, filter.params, "-fastq fastqfile -out_good accepted")
}

if (input.mode == "fa") {
	filter.command <- paste(binary.prinseq, filter.params, "-fasta fastqfile -out_good accepted")
}


if (log.file == "y") {
	system("echo Running PRINSEQ filtering with command: > filter.log")
	echo.command <- paste("echo '", filter.command, "'>> filter.log")
	system(echo.command)
	filter.command <- paste(filter.command, "-verbose 2>> filter.log")
}

system(filter.command)

#Make sure that something is given as an output
if (input.mode == "fq") {
	system("if [ ! -e  accepted.fastq ] ; then echo 'Filtering produced an empty accepted.fastq sequence set.' >> filter.log ; echo '' > accepted.fastq ; fi")
}

if (input.mode == "fa") {
	system("if [ ! -e  accepted.fasta ] ; then echo 'Filtering produced an empty accepted.fasta sequence set.' >> filter.log  ; echo '' > accepted.fasta ; fi")
}

if (output.mode == "both") {
	if (input.mode == "fq") {
		system("if [ ! -e  rejected.fastq ] ; then echo 'Filtering produced an empty rejected.fastq sequence set.' >> filter.log ; echo '' > rejected.fastq ; fi")
	}
	
	if (input.mode == "fa") {
		system("if [ ! -e  rejected.fasta ] ; then echo 'Filtering produced an empty rejected.fasta sequence set.' >> filter.log  ; echo '' > rejected.fasta ; fi")
	}
}

#stop
