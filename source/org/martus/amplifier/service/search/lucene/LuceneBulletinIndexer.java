package org.martus.amplifier.service.search.lucene;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.Collection;
import java.util.Iterator;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.DateField;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.martus.amplifier.service.search.BulletinField;
import org.martus.amplifier.service.search.BulletinIndexException;
import org.martus.amplifier.service.search.BulletinIndexer;
import org.martus.common.bulletin.AttachmentProxy;
import org.martus.common.packet.FieldDataPacket;
import org.martus.common.packet.UniversalId;

public class LuceneBulletinIndexer 
	implements BulletinIndexer, LuceneSearchConstants
{
	
	public LuceneBulletinIndexer(String baseDirName) 
		throws BulletinIndexException
	{
		indexDir = getIndexDir(baseDirName);
		try {
			createIndexIfNecessary(indexDir);
			writer = new IndexWriter(indexDir, getAnalyzer(), false);
		} catch (IOException e) {
			throw new BulletinIndexException(
				"Could not create LuceneBulletinIndexer", e);
		}		
	}
	
	public void close() throws BulletinIndexException
	{
		try {
			// TODO pdalbora 23-Apr-2003 -- Don't call this unnecessarily.
			writer.optimize();
		} catch (IOException e) {
			throw new BulletinIndexException("Unable to close the index", e);
		} finally {
			try {
				writer.close();
			} catch (IOException e) {
				throw new BulletinIndexException(
					"Unable to close the index", e);
			}		
		}		
	}
	
	public void clearIndex() throws BulletinIndexException
	{
		try {
			writer.close();
			writer = new IndexWriter(indexDir, new StandardAnalyzer(), true);
		} catch (IOException e) {
			throw new BulletinIndexException("Unable to clear the index", e);
		}		
	}
	
	public void indexFieldData(UniversalId bulletinId, FieldDataPacket fdp) 
		throws BulletinIndexException
	{
		Document doc = new Document();
		addBulletinId(doc, bulletinId);
		addFields(doc, fdp);
		addAttachmentIds(doc, fdp.getAccountId(), fdp.getAttachments());		
		
		try {
			writer.addDocument(doc);
		} catch (IOException e) {
			throw new BulletinIndexException(
				"Unable to index field data for " + bulletinId, e);
		}
	}
	
	/* package */ 
	static void createIndexIfNecessary(File indexDir)
		throws IOException
	{
		if (!IndexReader.indexExists(indexDir)) {
			IndexWriter writer = 
				new IndexWriter(indexDir, getAnalyzer(), true);
			writer.close();
		}
	}
	
	/* package */
	static Analyzer getAnalyzer()
	{
		return ANALYZER;
	}
	
	/* package */
	static File getIndexDir(String basePath)
		throws BulletinIndexException
	{
		File f = new File(basePath, INDEX_DIR_NAME);
		if (!f.exists() && !f.mkdirs()) {
			throw new BulletinIndexException(
				"Unable to create path: " + f);
		}
		return f;
	}
	
	private static void addBulletinId(Document doc, UniversalId bulletinId)
	{
		doc.add(Field.Keyword(
			BULLETIN_UNIVERSAL_ID_INDEX_FIELD, bulletinId.toString()));	
	}
	
	private static void addFields(Document doc, FieldDataPacket fdp) 
		throws BulletinIndexException 
	{
		Collection fields = BulletinField.getSearchableFields();
		for (Iterator iter = fields.iterator(); iter.hasNext();) {
			BulletinField field = (BulletinField) iter.next();
			String value = fdp.get(field.getXmlId());
			if ((value != null) && (value.length() > 0)) {
				addField(doc, field, value);
			}
		}
	}
	
	private static void addField(Document doc, BulletinField field, String value)
		throws BulletinIndexException
	{
		if (field.isDateField()||field.isDateRangeField()) 
		{
			doc.add(Field.Keyword(field.getIndexId(), 
				convertDateToSearchableString(value)));
		}
		else 
		{
			doc.add(Field.Text(field.getIndexId(), value));
		}
	}
	
	private static void addAttachmentIds(
		Document doc, String accountId, AttachmentProxy[] proxies)
	{
		if (proxies.length > 0) {
			StringBuffer buf = new StringBuffer();
			for (int i = 0; i < proxies.length; i++) {
				AttachmentProxy proxy = proxies[i];
				buf.append(proxy.getUniversalId().getLocalId());
				buf.append(ATTACHMENT_LIST_SEPARATOR);
				buf.append(proxy.getLabel());
				buf.append(ATTACHMENT_LIST_SEPARATOR);
			}
			doc.add(Field.UnIndexed(
				ATTACHMENT_LIST_INDEX_FIELD, buf.toString()));
		}
	}
	
	private static String convertDateToSearchableString(String dateString) 
		throws BulletinIndexException
	{
		try 
		{
			String date = DateField.dateToString(DATE_FORMAT.parse(dateString));
			return date;
		} 
		catch (ParseException e) 
		{
			throw new BulletinIndexException(
				"Unable to parse date " + dateString, e);
		} 
		catch (RuntimeException e) 
		{
			// NOTE pdalbora 30-Apr-2003 -- Some date objects cause the
			// dateToString() method to throw a RuntimeException() with
			// the message "time too early." This seems like a design flaw
			// on Lucene's part, since I only encountered this by mistake.
			// For now I'm just wrapping this exception, but this seems kind
			// of drastic. A date that's considered "too early" should perhaps
			// just be ignored w.r.t. the index.
			throw new BulletinIndexException(
				"Unable to convert date to indexable value: " + dateString, 
				e);
		}
	}
	
	private File indexDir;
	private IndexWriter writer;
	private final static Analyzer ANALYZER = new StandardAnalyzer();
	
	private static final String INDEX_DIR_NAME = "index";
}