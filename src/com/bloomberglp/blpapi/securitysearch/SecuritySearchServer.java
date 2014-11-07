package com.bloomberglp.blpapi.securitysearch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class SecuritySearchServer extends Thread {

	private volatile boolean d_exit = false;
	private String d_host = SecuritySearchApiConnection.DEFAULT_HOST;
	private int d_port = SecuritySearchApiConnection.DEFAULT_PORT;
	private int d_listenPort = 13377;
	private ServerSocket d_serverSocket = null;

	private void setPort(String newPort)
	{
		Integer.parseInt(newPort);
	}
	
	private void setHost(String newHost)
	{
		d_host = newHost;
	}
	
	private void setListenPort(String newListenPort)
	{
		Integer.parseInt(newListenPort);
	}
	
	public void run()
	{
		System.out.println("Starting...");
		System.out.println("Connecting to BLPAPI on " + d_host + ":" + d_port);
		while(!d_exit) {
			try {
				d_serverSocket = new ServerSocket(d_listenPort);
				//d_serverSocket.bind(new InetSocketAddress("localhost", d_listenPort));
				while(!d_exit)
				{
					try {
						System.out.println("Accepting connections on " + d_serverSocket.getInetAddress()
								           + " port " + d_serverSocket.getLocalPort());
						Socket clientSocket = d_serverSocket.accept();
						new SecuritySearchApiConnection(clientSocket, d_host, d_port).start();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				d_serverSocket.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public void exit()
	{
		d_exit = true;
		if(d_serverSocket != null)
		{
			try {
				d_serverSocket.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public void parseArgs(String[] args)
	{
		
		for(int i = 1; i < args.length; i = i + 2)
		{
			String parameter = args[i - 1];
			String newValue = args[i];
			if(parameter.compareTo("-p") == 0)
			{
				setPort(newValue);
			}
			else if(parameter.compareTo("-l") == 0)
			{
				setListenPort(newValue);
			}
			else if(parameter.compareTo("-h") == 0)
			{
				setHost(newValue);
			}
		}
	}
}
