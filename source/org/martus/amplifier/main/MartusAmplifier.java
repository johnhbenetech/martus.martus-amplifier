/*

The Martus(tm) free, social justice documentation and
monitoring software. Copyright (C) 2001-2004, Beneficent
Technology, Inc. (Benetech).

Martus is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either
version 2 of the License, or (at your option) any later
version with the additions and exceptions described in the
accompanying Martus license file entitled "license.txt".

It is distributed WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, including warranties of fitness of purpose or
merchantability.  See the accompanying Martus License and
GPL license for more details on the required license terms
for this software.

You should have received a copy of the GNU General Public
License along with this program; if not, write to the Free
Software Foundation, Inc., 59 Temple Place - Suite 330,
Boston, MA 02111-1307, USA.

*/
package org.martus.amplifier.main;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Vector;

import org.martus.amplifier.ServerCallbackInterface;
import org.martus.amplifier.attachment.DataManager;
import org.martus.amplifier.attachment.FileSystemDataManager;
import org.martus.amplifier.datasynch.BackupServerInfo;
import org.martus.amplifier.datasynch.DataSynchManager;
import org.martus.amplifier.lucene.LuceneBulletinIndexer;
import org.martus.amplifier.search.BulletinIndexException;
import org.martus.amplifier.search.BulletinIndexer;
import org.martus.common.MartusUtilities;
import org.martus.common.Version;
import org.martus.common.crypto.MartusCrypto;
import org.martus.common.crypto.MartusCrypto.CryptoInitializationException;
import org.martus.common.network.MartusXmlrpcClient.SSLSocketSetupException;
import org.martus.util.DirectoryUtils;
import org.mortbay.http.HttpContext;
import org.mortbay.http.SunJsseListener;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.ServletHandler;
import org.mortbay.util.InetAddrPort;
import org.mortbay.util.MultiException;

public class MartusAmplifier
{

	public MartusAmplifier(ServerCallbackInterface serverToUse) throws CryptoInitializationException
	{
		coreServer = serverToUse;
		setStaticSecurity(coreServer.getSecurity());
		
	}

	public void initalizeAmplifier(char[] password) throws Exception
	{
		staticAmplifierDirectory = coreServer.getDataDirectory();
		deleteLuceneLockFile();

		File indexDir = LuceneBulletinIndexer.getIndexDir(getStaticAmplifierDataPath());
		boolean isIndexObsolete;
		try
		{
			isIndexObsolete = LuceneBulletinIndexer.isIndexObsolete(indexDir);
		}
		catch(Exception e)
		{
			isIndexObsolete = true;
			log(e.getMessage());
		}
		
		if(isIndexObsolete)
		{
			log("ERROR: Index needs to be rebuilt to be compatible with this version of the search engine!");
			throw new Exception();
		}
		
		String packetsDirectory = getAmplifierPacketsDirectory().getPath();
		File webAuthorizedUserPasswordFile = getWebPasswordConfigurationFile();
		if(webAuthorizedUserPasswordFile.exists())
		{
			Vector webPassword = MartusUtilities.loadListFromFile(webAuthorizedUserPasswordFile);
			webAuthorizedUser = (String)webPassword.get(0);
			webAuthorizedPassword = (String)webPassword.get(1);
			webPasswordProtected = true;
			log("password Required Martus Web Search Engine");
		}
		else
		{
			webPasswordProtected = false;
			log("** No password Required for Martus Web Search Engine **");
		}

		dataManager = new FileSystemDataManager(packetsDirectory);
		
		File backupServersDirectory = getServersWhoWeCallDirectory();
		backupServersList = loadServersWeWillCall(backupServersDirectory, getSecurity());
		
		loadAccountsWeWillNotAmplify(getAccountsNotAmplifiedFile());
		log(notAmplifiedAccountsList.size() + " account(s) will not get amplified");

		File languagesIndexedFile = new File(indexDir, "languagesIndexed.txt");
		try
		{
			LanguagesIndexedList.initialize(languagesIndexedFile);
		}
		catch (Exception e)
		{
			log("Error: LanguagesIndex" + e);
		}

		File eventDatesIndexedFile = new File(indexDir, "eventDatesIndexed.txt");
		try
		{
			EventDatesIndexedList.initialize(eventDatesIndexedFile);
		}
		catch (Exception e)
		{
			log("Error: EventDatesIndex" + e);
		}
		
		//Code.setDebug(true);
		startServers(password);
	}

	private boolean isExceptionWeCareAbout(Exception e)
	{
		return e.getMessage().indexOf("jasper") < 0;
	}

	private File getAmplifierPacketsDirectory()
	{
		return new File(coreServer.getDataDirectory(), "ampPackets");
	}

	private void startServers(char[] password) throws Exception
	{
		try
		{
			log("Starting SSL server");
			startSSLServer(password);
		} catch (MultiException multi)
		{
			discardSillyExceptions(multi);
		}

		try
		{
			log("Starting non-SSL server");
			startNonSSLServer();
		} catch (MultiException multi)
		{
			discardSillyExceptions(multi);
		}
	}

	private void discardSillyExceptions(MultiException multi) throws Exception
	{
		int realExceptionCount = 0;
		for(int i = 0; i < multi.size(); ++i)
		{
			Exception e = multi.getException(i);
			if(isExceptionWeCareAbout(e))
			{
				e.printStackTrace();
				++realExceptionCount;
			}
		}
		if(realExceptionCount > 0)
			throw new Exception();
	}

	private void startNonSSLServer() throws IOException, MultiException
	{
		Server nonsslServer = new Server();
		
		int port = 80;
		if(coreServer.wantsDevelopmentMode())
			port += ServerCallbackInterface.DEVELOPMENT_MODE_PORT_DELTA;
		InetAddrPort nonssllistener = new InetAddrPort(port);
		nonsslServer.addWebApplication("/images/", getPresentationBasePath() + "presentationNonSSL/images");	
		nonssllistener.setInetAddress(getAmpIpAddress());
		nonsslServer.addListener(nonssllistener);
		
		HttpContext context = new HttpContext();
	    context.setContextPath("/");
	    nonsslServer.addContext(context);		
	    
	    ServletHandler servlets = new ServletHandler();
	    context.addHandler(servlets);
	    servlets.addServlet("Insecure", "/", "org.martus.amplifier.presentation.InsecureHomePage");		
		
		nonsslServer.start();
	}

	private void startSSLServer(char[] password) throws IOException, MultiException
	{
		int port = 443;
		if(coreServer.wantsDevelopmentMode())
			port += ServerCallbackInterface.DEVELOPMENT_MODE_PORT_DELTA;
		SunJsseListener sslListener = new SunJsseListener(new InetAddrPort(port));
		sslListener.setInetAddress(getAmpIpAddress());
		sslListener.setPassword(new String(password));
		sslListener.setKeyPassword(new String(password));
		sslListener.setMaxIdleTimeMs(MAX_IDLE_TIME_MS);
		sslListener.setMaxThreads(MAX_THREADS);
		sslListener.setMinThreads(MIN_THREADS);
		sslListener.setLowResourcePersistTimeMs(LOW_RESOURCE_PERSIST_TIME_MS);
		File jettyKeystore = getKeystoreFile();
		sslListener.setKeystore(jettyKeystore.getAbsolutePath());

		//File jettyXmlFile = new File(getStartupConfigDirectory(), "jettyConfiguration.xml");
		//Server sslServer = new Server(jettyXmlFile.getAbsolutePath());
		Server sslServer = new Server();

		sslServer.addWebApplication("", getPresentationBasePath() + "presentation");
		if(webPasswordProtected)
			addPasswordAuthentication(sslServer);
		sslServer.addListener(sslListener);
		sslServer.start();
	}

	public static MartusCrypto getStaticSecurity()
	{
		return staticSecurity;
	}
	
	public static void setStaticSecurity(MartusCrypto staticSecurityToUse)
	{
		staticSecurity = staticSecurityToUse;
	}
	
	public static String getStaticWebAuthorizedUser()
	{
		return webAuthorizedUser;
	}

	public static String getStaticWebAuthorizedPassword()
	{
		return webAuthorizedPassword;
	}
	
	public Vector getDeleteOnStartupFiles()
	{
		Vector startupFiles = new Vector();
		startupFiles.add(getKeystoreFile());
		startupFiles.add(getAccountsNotAmplifiedFile());
		startupFiles.add(getJettyConfigurationFile());
		startupFiles.add(getWebPasswordConfigurationFile());
		return startupFiles;
	}
	
	public Vector getDeleteOnStartupFolders()
	{
		Vector startupFolders = new Vector();
		startupFolders.add(getServersWhoWeCallDirectory());
		return startupFolders;
	}
	
	public void deleteAmplifierStartupFiles()
	{
		MartusUtilities.deleteAllFiles(getDeleteOnStartupFiles());
		DirectoryUtils.deleteEntireDirectoryTree(getDeleteOnStartupFolders());
	}

	public boolean canExitNow()
	{
		boolean isAmplifierSyncing = isAmplifierSyncing();
		if(!isAmplifierSyncing && !loggedCanExitYes)
		{	
			log("can exit now.");
			loggedCanExitNoAmpSyncing = false;
			loggedCanExitYes = true;
		}
		else if(isAmplifierSyncing && !loggedCanExitNoAmpSyncing)
		{	
			log("Unable to exit, amplifier Syncing.");
			loggedCanExitNoAmpSyncing = true;
			loggedCanExitYes = false;
		}
		return !isAmplifierSyncing;
	}
	
	public boolean isAmplifierSyncing()
	{
		return isSyncing;
	}
	
	public void startSynch()
	{
		isSyncing = true;
	}
	
	public void endSynch()
	{
		if(coreServer.isShutdownRequested())
			log("Shutdown requested and amp endSynch called");
		
		isSyncing = false;
	}

	public void pullNewDataFromServers() 
	{
		for(int i=0; i < backupServersList.size(); ++i)
		{
			if(coreServer.isShutdownRequested())
				return;
			BackupServerInfo backupServerToCall = (BackupServerInfo)backupServersList.get(i);
			pullNewDataFromOneServer(backupServerToCall);
		}
	}

	private void pullNewDataFromOneServer(BackupServerInfo backupServerToCall)
	{
		BulletinIndexer indexer = null;
		try
		{
			DataSynchManager dataSyncManager = new DataSynchManager(this, backupServerToCall, coreServer.getLogger(), getSecurity());
			indexer = new LuceneBulletinIndexer(MartusAmplifier.getStaticAmplifierDataPath());
		
			dataSyncManager.getAllNewData(dataManager, indexer, getListOfAccountsWeWillNotAmplify());
		}
		catch(Exception e)
		{
			log("MartusAmplifierDataSynch.execute(): " + e.getMessage());
			e.printStackTrace();
		} 
		finally
		{
			if (indexer != null) 
			{
				try 
				{
					indexer.close();
				} 
				catch (BulletinIndexException e) 
				{
					log("Unable to close the indexer: " + e.getMessage());
				}
			}
		}
	}

	public void loadAccountsWeWillNotAmplify(File notAmplifiedAccountsFile) throws IOException
	{
		if(notAmplifiedAccountsFile == null || !notAmplifiedAccountsFile.exists())
		{	
			notAmplifiedAccountsList = new Vector();
			return;
		}
		
		try
		{
			notAmplifiedAccountsList = MartusUtilities.loadListFromFile(notAmplifiedAccountsFile);
		}
		catch(Exception e)
		{
			log("Error: loadAccountsWeWillNotAmplify" + e);
			throw new IOException(e.toString());
		}
	}
	
	public List getListOfAccountsWeWillNotAmplify()
	{
		return notAmplifiedAccountsList;
	}
	
	public List loadServersWeWillCall(File directory, MartusCrypto security) throws 
			IOException, MartusUtilities.InvalidPublicKeyFileException, MartusUtilities.PublicInformationInvalidException, SSLSocketSetupException
	{
		List serversWeWillCall = new Vector();
	
		File[] toCallFiles = directory.listFiles();
		if(toCallFiles != null)
		{
			for (int i = 0; i < toCallFiles.length; i++)
			{
				File toCallFile = toCallFiles[i];
				if(!toCallFile.isDirectory())
				{
					serversWeWillCall.add(getServerToCall(toCallFile, security));
					log("will call: " + toCallFile.getName());
				}
			}
		}

		log("Configured to call " + serversWeWillCall.size() + " servers");
		return serversWeWillCall;
	}

	BackupServerInfo getServerToCall(File publicKeyFile, MartusCrypto security) throws
			IOException, 
			MartusUtilities.InvalidPublicKeyFileException, 
			MartusUtilities.PublicInformationInvalidException, 
			SSLSocketSetupException
	{
		String ip = MartusUtilities.extractIpFromFileName(publicKeyFile.getName());
		int port = 985;
		Vector publicInfo = MartusUtilities.importServerPublicKeyFromFile(publicKeyFile, security);
		String publicKey = (String)publicInfo.get(0);
	
		return new BackupServerInfo(ip, ip, port, publicKey);		
	}
	
	void log(String message)
	{
		coreServer.log("Amp: " + message);
	}
	
	public static String getPresentationBasePath()
	{
		String presentationBasePath = null;
		if(Version.isRunningUnderWindows())
		{	
			File amplifierPath = new File(MartusAmplifier.class.getResource("MartusAmplifier.class").getPath());
			File amplifierBasePath = amplifierPath.getParentFile().getParentFile().getParentFile().getParentFile().getParentFile();
			if(amplifierBasePath.getPath().endsWith("classes"))
				amplifierBasePath = amplifierBasePath.getParentFile();
			presentationBasePath = amplifierBasePath.getPath();
			if(!presentationBasePath.endsWith("\\"))
				presentationBasePath += "\\";
		}
		else
			presentationBasePath = "/usrlocal/martus/www/MartusAmplifier/";
		return presentationBasePath;
		
	}

	public boolean isShutdownRequested()
	{
		return coreServer.isShutdownRequested();
	}
	
	static public MartusCrypto getSecurity()
	{
		return staticSecurity;
	}

	private File getServersWhoWeCallDirectory()
	{
		return new File(coreServer.getStartupConfigDirectory(), SERVERS_WHO_WE_CALL_DIRIRECTORY);
	}

	private File getAccountsNotAmplifiedFile()
	{
		return new File(coreServer.getStartupConfigDirectory(), ACCOUNTS_NOT_AMPLIFIED_FILE);
	}

	private File getJettyConfigurationFile()
	{
		return new File(coreServer.getStartupConfigDirectory(), JETTY_CONFIGURATION_FILE);
	}
	
	private File getWebPasswordConfigurationFile()
	{
		return new File(coreServer.getStartupConfigDirectory(), WEB_PASSWORD_CONFIGURATION_FILE);
	}
	
	private File getKeystoreFile()
	{
		return new File(coreServer.getStartupConfigDirectory(), KEYSTORE_FILE);
	}

	private InetAddress getAmpIpAddress() throws UnknownHostException
	{
		return InetAddress.getByName(coreServer.getAmpIpAddress());
	}

	private void deleteLuceneLockFile() throws BulletinIndexException
	{
		File indexDirectory = LuceneBulletinIndexer.getIndexDir(MartusAmplifier.getStaticAmplifierDataPath());
		File lockFile = new File(indexDirectory, "write.lock");
		if(lockFile.exists())
		{
			log("Deleting lucene lock file: " + lockFile.getPath());
			lockFile.delete();
		}
	}

	private void addPasswordAuthentication(Server server)
	{
		PasswordAuthenticationHandler handler = new PasswordAuthenticationHandler();
		HttpContext context = server.getContext("/");
		context.addHandler(handler);
	}
	
	public static String getStaticAmplifierDataPath()
	{
		return staticAmplifierDirectory.getPath();
	}

	boolean isSyncing;
	private List backupServersList;
	List notAmplifiedAccountsList;
	ServerCallbackInterface coreServer;
	private boolean loggedCanExitNoAmpSyncing;
	private boolean loggedCanExitYes;
	private boolean webPasswordProtected;
	
	private static final String SERVERS_WHO_WE_CALL_DIRIRECTORY = "serversWhoWeCall";
	private static final String ACCOUNTS_NOT_AMPLIFIED_FILE = "accountsNotAmplified.txt";
	private static final String JETTY_CONFIGURATION_FILE = "jettyConfiguration.xml";
	private static final String WEB_PASSWORD_CONFIGURATION_FILE = "webauthorized.txt";
	private static final String KEYSTORE_FILE = "keystore";
	
	static final long IMMEDIATELY = 0;
	public static final long DEFAULT_HOURS_TO_SYNC = 24;
	
	private static final int LOW_RESOURCE_PERSIST_TIME_MS = 5000;

	private static final int MAX_IDLE_TIME_MS = 30000;
	private static final int MIN_THREADS = 5;
	private static final int MAX_THREADS = 255;

	// NOTE: The following members *MUST* be static because they are 
	// used by servlets that do not have access to an amplifier object! 
	// USE THEM CAREFULLY!
	public static DataManager dataManager;
	public static File staticAmplifierDirectory;
	private static MartusCrypto staticSecurity;
	private static String webAuthorizedUser;
	private static String webAuthorizedPassword;
}
