# TOOL htseq-count.R: "Count aligned reads per genes with HTSeq" (Calculates how many reads in a BAM file map to each gene. If you would like to map reads against your own GTF files, please use the tool \"Count aligned reads per genes with HTSeq using own GTF\". This tool is based on the HTSeq package. In order to use the output in edgeR or DESeq, you need to select all samples and run the tool \"Utilities - Define NGS experiment\".)
# INPUT alignment.bam: "BAM alignment file" TYPE GENERIC
# OUTPUT htseq-counts.tsv 
# OUTPUT OPTIONAL htseq-count-info.txt
# PARAMETER organism: "Organism" TYPE [Homo_sapiens.GRCh37.68: "Human (hg19)", Mus_musculus.GRCm38.68: "Mouse (mm10)", Mus_musculus.NCBIM37.62: "Mouse (mm9)", Rattus_norvegicus.RGSC3.4.68: "Rat (rn4)"] DEFAULT Homo_sapiens.GRCh37.68 (Which organism is your data from.)
# PARAMETER chr: "Chromosome names in my BAM file look like" TYPE [yes: "chr1", no: "1"] DEFAULT yes (Chromosome names must match in the BAM file and in the reference annotation. Check your BAM and choose accordingly.)
# PARAMETER paired: "Does the alignment file contain paired-end data" TYPE [yes, no] DEFAULT no (Does the alignment data contain paired end or single end reads?)
# PARAMETER stranded: "Was the data produced with a strand-specific RNA-seq protocol" TYPE [yes, no, reverse] DEFAULT no (If you select no, a read is considered overlapping with a feature regardless of whether it is mapped to the same or the opposite strand as the feature. If you select yes, the read has to be mapped to the same strand as the feature. You have to say no, if your was not made with a strand-specific RNA-seq protocol, because otherwise half your reads will be lost.)
# PARAMETER OPTIONAL mode: "Mode to handle reads overlapping more than one feature" TYPE [union, intersection-strict, intersection-nonempty] DEFAULT union (How to deal with reads that overlap more than one gene or exon?)
# PARAMETER OPTIONAL minaqual: "Minimum alignment quality" TYPE INTEGER FROM 0 TO 100 DEFAULT 0 (Skip all reads with alignment quality lower than the given minimum value.)
# PARAMETER OPTIONAL feature.type: "Feature type to count" TYPE [exon, CDS] DEFAULT exon (Which feature type to use, all features of other type are ignored.)
# PARAMETER OPTIONAL id.attribute: "Feature ID to use" TYPE [gene_id, transcript_id, gene_name, transcript_name, protein_name] DEFAULT gene_id (GFF attribute to be used as feature ID. Several GFF lines with the same feature ID will be considered as parts of the same feature. The feature ID is used to identify the counts in the output table.)
# PARAMETER OPTIONAL print.coord: "Add chromosomal coordinates to the count table" TYPE [yes, no] DEFAULT yes (If you select yes, chromosomal coordinates are added to the output file. Given are the minimum and maximum coordinates of features, e.g. exons, associated with a given identifier)

# 18.1.2012 TH and EK 
# 17.4.2012 EK changed to use Ensembl GTFs 
# 3.2.2013 AMS added chr/nochr option
# 6.5.2013 MK added chr-location information to the output
# 30.5.2013 EK changed the default for "add chromosomal coordinates" to no

# bash wrapping
python.path <- paste(sep="", "PYTHONPATH=", file.path(chipster.tools.path, "lib", "python2.6", "site-packages"), ":$PYTHONPATH")
command.start <- paste("bash -c '", python.path, ";")
command.end <- "'"

# sort bam if the data is paired-end
samtools.binary <- file.path(chipster.tools.path, "samtools", "samtools")
samtools.sort <- ifelse(paired == "yes", paste(samtools.binary, "sort -on alignment.bam sorted-by-name"), "cat alignment.bam")

# convert bam to sam
samtools.view <- paste(samtools.binary, "view -")

# htseq-count
if(print.coord == "no") {
	htseq.binary <- file.path(chipster.tools.path, "htseq", "htseq-count")
} else {
	htseq.binary <- file.path(chipster.tools.path, "htseq", "htseq-count_chr")
}

if(chr == "yes"){
	organism <- paste(organism, ".chr.gtf", sep="")
}
if(chr == "no"){
	organism <- paste(organism, ".gtf", sep="")
}
gtf <- file.path(chipster.tools.path, "genomes", "gtf", organism)
htseq <- paste(htseq.binary, "-q -m", mode, "-s", stranded, "-a", minaqual, "-t", feature.type, "-i", id.attribute, "-", gtf, " > htseq-counts-out.txt")

# run
command <- paste(command.start, samtools.sort, " | ", samtools.view, " | ", htseq, command.end)
system(command)

# separate result file
system("head -n -5 htseq-counts-out.txt > htseq-counts.tsv")
system("tail -n 5 htseq-counts-out.txt > htseq-count-info.txt")

# bring in file to R environment for formating
file <- c("htseq-counts.tsv")
dat <- read.table(file, header=F, sep="\t")

if(print.coord == "no") {
	names(dat) <- c("id", "count")
} else {
	names(dat) <- c("id", "chr", "start", "end", "len", "strand", "count")
}

# write result table to output
write.table(dat, file="htseq-counts.tsv", col.names=T, quote=F, sep="\t", row.names=F)

# EOF

