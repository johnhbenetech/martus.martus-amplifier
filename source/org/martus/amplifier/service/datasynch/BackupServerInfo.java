package org.martus.amplifier.service.datasynch;

public class BackupServerInfo
{

	public BackupServerInfo(String newName, String newAddress, int newPort, String newServerPublicKey)
	{
		name = newName;
		port = newPort;
		address = newAddress;
		serverPublicKey = newServerPublicKey;
	}

	private String name = null;
	private String address = null;
	private int port = 0;
	private String serverPublicKey = null;
	
	public String getAddress()
	{
		return address;
	}

	public String getName()
	{
		return name;
	}

	public int getPort()
	{
		return port;
	}
	
	public String getServerPublicKey()
	{
		return serverPublicKey;
	}

}
