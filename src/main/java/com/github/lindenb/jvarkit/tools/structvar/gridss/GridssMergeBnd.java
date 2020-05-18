/*
The MIT License (MIT)

Copyright (c) 2020 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

*/
package com.github.lindenb.jvarkit.tools.structvar.gridss;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParametersDelegate;
import com.github.lindenb.jvarkit.io.IOUtils;
import com.github.lindenb.jvarkit.lang.JvarkitException;
import com.github.lindenb.jvarkit.util.JVarkitVersion;
import com.github.lindenb.jvarkit.util.bio.SequenceDictionaryUtils;
import com.github.lindenb.jvarkit.util.jcommander.Launcher;
import com.github.lindenb.jvarkit.util.jcommander.Program;
import com.github.lindenb.jvarkit.util.log.Logger;
import com.github.lindenb.jvarkit.util.picard.AbstractDataCodec;
import com.github.lindenb.jvarkit.variant.variantcontext.writer.WritingVariantsDelegate;
import com.github.lindenb.jvarkit.variant.vcf.BcfIteratorBuilder;

import htsjdk.samtools.QueryInterval;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Locatable;
import htsjdk.samtools.util.PeekableIterator;
import htsjdk.samtools.util.SequenceUtil;
import htsjdk.samtools.util.SortingCollection;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFConstants;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFIterator;
import htsjdk.variant.vcf.VCFStandardHeaderLines;
/**
BEGIN_DOC

Input is a set of VCF files or a file with the '.list' suffix containing the paths to the vcfs

END_DOC
 */
@Program(name="gridssmergebnd",
	generate_doc=false,
	description="Merge BND results from gridss",
	keywords= {"cnv","indel","sv"},
	creationDate="20200517",
	modificationDate="20200518"
	)
public class GridssMergeBnd extends Launcher{
	private static final Logger LOG = Logger.build(GridssMergeBnd.class).make();
	@Parameter(names={"-o","--out"},description=OPT_OUPUT_FILE_OR_STDOUT)
	private Path outputFile=null;
	@Parameter(names={"-d","--distance"},description="within distance. Two bnd are considered the same they're withing that distance.")
	int withinDistance = 0;
	@ParametersDelegate
	private WritingVariantsDelegate writingVariantsDelegate = new WritingVariantsDelegate();
	@ParametersDelegate
	private WritingSortingCollection sortingDelegate = new WritingSortingCollection();

	private SAMSequenceDictionary theDict = null;
	
	private class BreakPoint implements Comparable<BreakPoint>,Locatable {
		final int tid;
		final int start;
		final int end;
		final int sample_id;
		
		BreakPoint(int tid,int start,int end,int sample_id) {
			this.tid=tid;
			this.start=start;
			this.end=end;
			this.sample_id = sample_id;
		}
		
		@Override
		public String getContig()
			{
			return theDict.getSequence(this.tid).getSequenceName();
			}
		@Override
		public int getStart()
			{
			return start;
			}
		@Override
		public int getEnd()
			{
			return end;
			}
		@Override
		public int compareTo(final BreakPoint o)
			{
			int i= Integer.compare(this.tid, o.tid);
			if(i!=0) return i;
			return Integer.compare(this.start, o.start);
			}
		@Override
		public String toString() {
			return getContig()+":"+getStart()+"-"+getEnd()+" "+sample_id;
			}
		}
	
	private class BreakPointCodec extends AbstractDataCodec<BreakPoint> {
		@Override
		public BreakPoint decode(DataInputStream dis) throws IOException
			{
			int tid;
			try {
				tid = dis.readInt();
				}
			catch(EOFException err) {
				return null;
				}
			int start = dis.readInt();
			int end = dis.readInt();
			int sample_id = dis.readInt();
			return new BreakPoint(tid,start,end,sample_id);
			}
	
		@Override
		public void encode(DataOutputStream dos, final BreakPoint o)
				throws IOException
			{
			dos.writeInt(o.tid);
			dos.writeInt(o.start);
			dos.writeInt(o.end);
			dos.writeInt(o.sample_id);
			}
		
		@Override
		public AbstractDataCodec<BreakPoint> clone()
			{
			return new BreakPointCodec();
			}
		}
	
	@Override
	public int doWork(List<String> args)
		{
		try {
			final Map<String,Integer> sample2index = new HashMap<>();			
			final List<String> idx2sample = new ArrayList<>();
			
			SortingCollection<BreakPoint> sorter = SortingCollection.newInstance(
					BreakPoint.class,
					new BreakPointCodec(),(A,B)->A.compareTo(B),
					this.sortingDelegate.maxRecordsInRam,
					this.sortingDelegate.getTmpPaths()
					);
			sorter.setDestructiveIteration(true);
			
			for(final Path path: IOUtils.unrollPaths(args)) {
				LOG.info("Reading "+path);
				try(VCFIterator iter= new BcfIteratorBuilder().open(path)) {
					final VCFHeader header= iter.getHeader();
					final List<String> vcfsamples;
					if(header.getNGenotypeSamples()>0) {
						vcfsamples = header.getSampleNamesInOrder();
						}
					else
						{
						vcfsamples = Collections.singletonList(path.toString());
						}
					
					for(final String sn: vcfsamples) {
						if(sample2index.containsKey(sn)) {
							LOG.error("Duplicate sample "+sn+" from "+path);
							return -1;
							}
						final int sample_id = idx2sample.size();
						idx2sample.add(sn);
						sample2index.put(sn, sample_id);
						}
					
					
					final SAMSequenceDictionary dict = SequenceDictionaryUtils.extractRequired(header);
					if(this.theDict==null) {
						this.theDict = dict;
						}
					else
						{
						SequenceUtil.assertSequenceDictionariesEqual(dict, this.theDict);
						}
					
					
					while(iter.hasNext()) {
						final VariantContext ctx = iter.next();
						final int tid = this.theDict.getSequenceIndex(ctx.getContig());
						if(tid==-1) throw new JvarkitException.ContigNotFoundInDictionary(ctx.getContig(), this.theDict);
						int start = ctx.getStart();
						int end = ctx.getStart();//yes start
						if(ctx.hasAttribute("CIPOS")) {
							final List<Integer> cipos = ctx.getAttributeAsIntList("CIPOS", 0);
							if(cipos.size()>0) start += cipos.get(0);
							if(cipos.size()>1) end += cipos.get(1);
							if(start>end) {
								final int tmp = start;
								start = end;
								end = tmp;
								}
							}
						final QueryInterval qi1 = new QueryInterval(tid, start, end);
						final QueryInterval qintervals[];
						
						if(!ctx.getAttributeAsString(VCFConstants.SVTYPE,"undefined").equals("BND") &&
							ctx.getLengthOnReference()>this.withinDistance) {
							int start2 =  ctx.getEnd();
							int end2 =  ctx.getEnd();
							if(ctx.hasAttribute("CIEND")) {
								final List<Integer> ciend = ctx.getAttributeAsIntList("CIEND", 0);
								if(ciend.size()>0) start2 += ciend.get(0);
								if(ciend.size()>1) end2 += ciend.get(1);
								if(start2>end2) {
									final int tmp = start2;
									start2 = end2;
									end2 = tmp;
									}
								}
							final QueryInterval qi2 = new QueryInterval(tid, start2, end2);
							qintervals = new QueryInterval[]{qi1,qi2};
							}
						else
							{
							qintervals = new QueryInterval[]{qi1};
							}
						if(ctx.hasGenotypes()) {
							final int sample_id = idx2sample.size()-1;
							for(QueryInterval qi: qintervals) {
								sorter.add(new BreakPoint(qi.referenceIndex, qi.start, qi.end, sample_id));
								}
							}
						else 
							{
							for(int gidx=0;gidx< ctx.getNSamples();gidx++) {
								final Genotype gt = ctx.getGenotype(gidx);
								if(gt.isHomRef() || gt.isNoCall()) continue;
								final int sample_id = sample2index.get(gt.getSampleName());
								for(QueryInterval qi: qintervals) {
									sorter.add(new BreakPoint(qi.referenceIndex, qi.start, qi.end, sample_id));
									}
								}
							}
						}
						
					}
				}
			
			sorter.doneAdding();
			LOG.info("Done sorting");
			
			final Set<VCFHeaderLine> metaData = new HashSet<>();
			VCFStandardHeaderLines.addStandardInfoLines(metaData, true,VCFConstants.END_KEY,VCFConstants.ALLELE_COUNT_KEY,VCFConstants.ALLELE_FREQUENCY_KEY,VCFConstants.ALLELE_NUMBER_KEY);
			VCFStandardHeaderLines.addStandardFormatLines(metaData, true,VCFConstants.GENOTYPE_KEY);
			final VCFHeader header = new VCFHeader(metaData, new TreeSet<>(sample2index.keySet()));
			header.setSequenceDictionary(this.theDict);
			JVarkitVersion.getInstance().addMetaData(this, header);
			
			VariantContextWriter vcw = this.writingVariantsDelegate.
						dictionary(this.theDict).
						open(this.outputFile);
			vcw.writeHeader(header);
			
			final Allele ref_allele = Allele.create("N", true);
			final Allele alt_allele = Allele.create("<BND>", false);
			final List<Allele> alleles = Arrays.asList(ref_allele,alt_allele);
			try(CloseableIterator<BreakPoint> iter = sorter.iterator())  {
				PeekableIterator<BreakPoint> peek = new PeekableIterator<>(iter);
				while(peek.hasNext()) {
					final BreakPoint first = peek.next();
					final List<BreakPoint> array = new ArrayList<GridssMergeBnd.BreakPoint>();
					array.add(first);
					while(peek.hasNext()) {
						final BreakPoint bp2 = peek.peek();
						if(array.stream().noneMatch(BP->BP.withinDistanceOf(bp2,this.withinDistance))) {
							break;
							}
						array.add(peek.next());
						}
					
					final Set<Integer> uniq_sample_idx = array.stream().map(BP->BP.sample_id).collect(Collectors.toSet());

					final int end = array.stream().mapToInt(BP->BP.end).max().orElse(first.end);
					
					final VariantContextBuilder vcb = new VariantContextBuilder(null,first.getContig(),first.getStart(),end,alleles);
					if(first.getStart()!=end) vcb.attribute(VCFConstants.END_KEY,end);
					vcb.attribute(VCFConstants.ALLELE_COUNT_KEY, uniq_sample_idx.size());
					vcb.attribute(VCFConstants.ALLELE_NUMBER_KEY, idx2sample.size()*2);
					vcb.attribute(VCFConstants.ALLELE_FREQUENCY_KEY, uniq_sample_idx.size()/(2.0*idx2sample.size()));
					vcb.genotypes(array.
						stream().
						map(BP->new GenotypeBuilder(idx2sample.get(BP.sample_id), alleles).make()).
						collect(Collectors.toList()));
					vcw.add(vcb.make());
					}
				peek.close();
				}
			vcw.close();
			sorter.cleanup();
			return 0;
			}
		catch(final Throwable err) {
			LOG.error(err);
			return -1;
			}
		finally {
		
			}
		}
	public static void main(final String[] args) {
		new GridssMergeBnd().instanceMainWithExit(args);
		}
	}
