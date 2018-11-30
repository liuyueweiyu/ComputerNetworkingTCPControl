package com.ouc.tcp.test;


import com.ouc.tcp.client.Client;
import com.ouc.tcp.message.TCP_PACKET;

public class Window {
	public int windowSize = 10;
	public Client client;
	public TCP_PACKET[] packets = new TCP_PACKET[windowSize];
	public volatile int begin = 0,now = 0, end = windowSize - 1;
	public int sequence = 1;
	public boolean[] checkAck = new boolean[windowSize];

	/*¹¹Ôìº¯Êý*/
	public Window(Client client) {
		super();
		this.client = client;
	}		
	

}
