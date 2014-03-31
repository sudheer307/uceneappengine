package com.googlecode.luceneappengine;

import static com.googlecode.objectify.ObjectifyService.factory;
import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.util.RamUsageEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.googlecode.luceneappengine.objectify.util.ObjectifyBuilder;
import com.googlecode.luceneappengine.objectify.util.ObjectifyUtil;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.NotFoundException;
import com.googlecode.objectify.Objectify;
import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.Work;
import com.googlecode.objectify.cache.PendingFutures;

/**
 * Lucene {@link Directory} working in google app engine (GAE) environment.
 * Using this {@link Directory} you can create multiple indexes, each one identified by
 * a name specified in contructor {@link GaeDirectory#GaeDirectory(String)}, 
 * for details read constructor documentation.
 * <b>In order to open an index writer is highly recommended the usage of configuration provided by 
 * {@link GaeLuceneUtil#getIndexWriterConfig(org.apache.lucene.util.Version, org.apache.lucene.analysis.Analyzer)}</b>.
 * <pre>
 * {@code
 * GaeDirectory directory = new GaeDirectory();
 * IndexWriterConfig config = GaeLuceneUtil.getIndexWriterConfig(Version.LUCENE_36, analyzer);
 * IndexWriter writer = new IndexWriter(directory, config);
 * }
 * </pre>
 * <br />
 * <i>
 * If your application throws {@link NoClassDefFoundError} while using {@link GaeDirectory} 
 * in order to make it work, into your GAE web application, the modified {@link RamUsageEstimator} 
 * (<a href="http://lucene-appengine.googlecode.com/hg/src/main/java/org/apache/lucene/util/RamUsageEstimator.java">link to source</a>) 
 * in a package named <code>org.apache.lucene.util</code>.
 * </i>
 * 
 * @author Fabio Grucci (github: <i>UltimaPhoenix</i>, bitbucket: <i>Dark_Phoenix</i>, googlecode: <i>fabio.grucci</i>)
 * @see GaeLuceneUtil
 * @see RamUsageEstimator
 */
public class GaeDirectory extends Directory {

	private static final Logger log = LoggerFactory.getLogger(GaeDirectory.class);
	
	private static final String DEFAULT_GAE_LUCENE_INDEX_NAME = "defaultIndex";
	
	private final Key<LuceneIndex> indexKey;
	
	static {
		ObjectifyFactory instance = factory();
		instance.register(com.googlecode.luceneappengine.GaeLock.class);
		instance.register(com.googlecode.luceneappengine.LuceneIndex.class);
		instance.register(com.googlecode.luceneappengine.Segment.class);
		instance.register(com.googlecode.luceneappengine.SegmentHunk.class);
	}
	 
	/**
	 * Create a {@link GaeDirectory} with default name <code>"defaultIndex"</code>.
	 * Same as {@link GaeDirectory#GaeDirectory(String)} with <code>"defaultIndex"</code>.
	 */
	public GaeDirectory() {
		this(DEFAULT_GAE_LUCENE_INDEX_NAME);
	}
	/**
	 * Create a {@link GaeDirectory} with specified name, 
	 * <b>the name specified must be a legal {@link com.google.appengine.api.datastore.Key} name</b>.
	 * @param indexName The name of the index
	 */
	public GaeDirectory(String indexName) {
		if(indexName == null) {
			indexName = DEFAULT_GAE_LUCENE_INDEX_NAME;
		}
		this.indexKey = createIndexIfNotExist(indexName);
		try {
			setLockFactory(GaeLockFactory.getInstance(indexKey));
		} catch (IOException e) {
			// Cannot happen.
			log.error("Unhandled Exception please report this stack trace to code mantainer, " +
					"this index can be corrupted with concurrent indexing operations.", e);
		}
	}
	/**
	 * Create a {@link GaeDirectory} for existing index.
	 * @param luceneIndex The existing index
	 */
	public GaeDirectory(LuceneIndex luceneIndex) {
		this(luceneIndex.getName());
	}
	
	/**
	 * Returns every available indexes created into your GAE application.
	 * With {@link LuceneIndex} you can build {@link GaeDirectory} 
	 * using constructor {@link GaeDirectory#GaeDirectory(LuceneIndex)}.
	 * @return A list of available indexes
	 */
	public static List<LuceneIndex> getAvailableIndexes() {
		return ofy().load().type(LuceneIndex.class).list();
	}
	/**
	 * Delete this directory.
	 * @throws IOException If an error occurs
	 */
	public void delete() throws IOException {
		final Objectify objectify = ofy();
		objectify.transactNew(3, new Work<Void>() {
			@Override
			public Void run() {
				for(String name : listAll())
					deleteSegment(objectify, name);
				objectify.delete().key(indexKey);
				objectify.delete().entities(((GaeLockFactory) getLockFactory()).getLocks());
				return null;
			}
		});
	}
	/**
	 * Delete the segment specified.
	 * @param name The name of the segment
	 */
	protected void deleteSegment(final String name) {
		final Objectify objectify = ofy();
		objectify.transactNew(4, new Work<Void>() {
			@Override
			public Void run() {
				deleteSegment(objectify, name);
				return null;
			}
		});
	}
	/**
	 * Delete the segment using the specified {@link Objectify} usefull for transaction.
	 * @param objectify The {@link Objectify} to use
	 * @param name The name of the segment to delete
	 */
	protected void deleteSegment(final Objectify objectify, final String name) {
		final Key<Segment> segmentKey = newSegmentKey(name);
		
		final Segment segment = objectify.load().key(segmentKey).now();
		
		objectify.delete().keys(segment.getHunkKeys(segmentKey));
		objectify.delete().key(segmentKey);
	}
	
	/**
	 * Method used for testing purpose useful for printing segment information.
	 * @param segment The segment to print
	 * @param name The name of the hunk to print
	 * @param index The index of the hunk to print
	 */
	protected void logSegment(Segment segment, String name, int index) {
		Objectify objectify = ofy();
		final SegmentHunk hunk = objectify.load().key(newSegmentHunkKey(name, index)).now();
		byte[] content = Arrays.copyOfRange(hunk.bytes, 0, (int) (hunk.bytes.length % (segment.length / hunk.id)));
		hunk.bytes = content;
		log.info("Hunk '{}-{}-{}' with length {}, Value={}", 
				indexKey.getName(), name, hunk.id, hunk.bytes.length, new String(hunk.bytes));
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#close()
	 */
	@Override
	public void close() throws IOException {
		/* nothing to do */
		//TODO: refactor for caching. Doh! There's something to do!
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#openInput(java.lang.String, org.apache.lucene.store.IOContext)
	 */
	@Override
	public IndexInput openInput(String name, IOContext context) throws IOException {
		try {
			PendingFutures.completeAllPendingFutures();
			return new SegmentIndexInput(ofy().load().key(newSegmentKey(name)).safe());
		} catch (NotFoundException e) {
			throw new IOException(name, e);
		}
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#createOutput(java.lang.String, org.apache.lucene.store.IOContext)
	 */
	@Override
	public IndexOutput createOutput(String name, IOContext context) throws IOException {
		final Objectify begin = ofy();
		Segment segment = begin.load().key(newSegmentKey(name)).now();
		if(segment == null) {
			segment = newSegment(name);
			PendingFutures.completeAllPendingFutures();
		}
		return new SegmentIndexOutput(segment);
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#deleteFile(java.lang.String)
	 */
	@Override
	public void deleteFile(String name) throws IOException {
		final Objectify objectify = ofy();
		final Segment segment = objectify.load().key(newSegmentKey(name)).now();
		
		final long hunkCount = segment.hunkCount;
		for (int i = 1; i <= hunkCount; i++) {
			objectify.delete().key(newSegmentHunkKey(name, i));
		}
		objectify.delete().key(newSegmentKey(name));
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#fileExists(java.lang.String)
	 */
	@Override
	public boolean fileExists(String name) throws IOException {
		final Segment bigTableIndexFile = ofy().load().key(newSegmentKey(name)).now();
		return bigTableIndexFile != null;
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#fileLength(java.lang.String)
	 */
	@Override
	public long fileLength(String name) throws IOException {
		final Segment bigTableIndexFile = ofy().load().key(newSegmentKey(name)).now();
		return bigTableIndexFile.length;
	}
	/*
	 * (non-Javadoc)
	 * @see org.apache.lucene.store.Directory#listAll()
	 */
	@Override
	public String[] listAll() {
		final Objectify objectify = ofy();
		final List<Key<Segment>> keys = objectify.load().type(Segment.class).ancestor(indexKey).keys().list();
		String[] names = new String[keys.size()];
		int i = 0;
		for (Key<Segment> name : keys)
			names[i++] = name.getName();
		return names;
	}
	/**
	 * Create a new segment with the specified name using the specified {@link Objectify}.
	 * The {@link Segment} contains one empty {@link SegmentHunk}.
	 * @param name The name of the segment to create
	 * @param objectify The objectify to use
	 * @return A new {@link Segment} with one {@link SegmentHunk} 
	 */
	protected Segment newSegment(String name) {
		Segment segment = new Segment(indexKey, name);
		SegmentHunk newHunk = segment.newHunk();//at least one segment
		segment.lastModified = System.currentTimeMillis();
		ofy().save().entities(segment, newHunk).now();
		log.debug("Created segment '{}'.", name);
		return segment;
	}
	
	private Key<SegmentHunk> newSegmentHunkKey(final String name, long count) {
		return Key.create(newSegmentKey(name), SegmentHunk.class, count);
	}
	private Key<Segment> newSegmentKey(final String name) {
		return Key.create(indexKey, Segment.class, name);
	}
	private static Key<LuceneIndex> createIndexIfNotExist(String indexName) {
		Key<LuceneIndex> key = Key.create(LuceneIndex.class, indexName);
		ObjectifyUtil.getOrCreate(key, new LuceneIndexBuilder());
		return key;
	}
	
	private static class LuceneIndexBuilder implements ObjectifyBuilder<LuceneIndex>{
		@Override
		public LuceneIndex newIstance(Key<LuceneIndex> key) {
			return new LuceneIndex(key.getName());
		}
	}

	@Override
	public void sync(Collection<String> arg0) throws IOException {
		PendingFutures.completeAllPendingFutures();
	}
}