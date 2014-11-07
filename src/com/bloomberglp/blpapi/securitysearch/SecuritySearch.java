package com.bloomberglp.blpapi.securitysearch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class SecuritySearch {
	
	public static void main(String[] args)
	{
		SecuritySearchServer sss = new SecuritySearchServer();
		sss.parseArgs(args);
		sss.start();
		
		while(true)
		{
		    try {
		    	System.out.println("Write quit to quit!");
		    	BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
		    	if(br.readLine().compareToIgnoreCase("quit") == 0)
		    	{
		    		sss.exit();
		    		System.out.println("Exiting!");
		    		break;
		    	}
		    } catch (IOException io) {
		    	System.out.println("IO error trying to read input!");
		    }
		}
	}
}
