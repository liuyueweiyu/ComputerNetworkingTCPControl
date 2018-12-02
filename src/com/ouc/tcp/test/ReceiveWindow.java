package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Vector;

import com.ouc.tcp.client.Client;
import com.ouc.tcp.message.TCP_PACKET;


public class ReceiveWindow extends Window {

	public ReceiveWindow(Client client) {
		super(client);
	}
	
	public Vector reply_select(TCP_PACKET recvPack,TCP_PACKET packet) {
		Vector result = new Vector();
		int index = packet.getTcpH().getTh_ack()/100;
		if(index >= 0 ) {
			index = index % windowSize;
			checkAck[index] = true;
			packets[index] = recvPack;
			client.send(packet);
			if(index == begin%windowSize) {
				int j = begin;
				for(;j<=end&&checkAck[j%windowSize];j++) {
					int jdenx = j%windowSize;
					result.addElement(packets[jdenx].getTcpS().getData());
					checkAck[jdenx] = false;
				}
				begin = j ;
				end = begin + windowSize - 1;
				sequence = packets[(begin-1)%windowSize].getTcpH().getTh_seq();
				return result;
			}
		}
		return null;
	}
	public Vector reply_BGN(TCP_PACKET recvPack,TCP_PACKET packet) {
		Vector result = new Vector();
		client.send(packet);
		if(recvPack.getTcpH().getTh_seq() == sequence) {
			receiveWindowlog_receive(sequence);
			result.addElement(recvPack.getTcpS().getData());
			sequence += recvPack.getTcpS().getData().length;
			return result;
		}
		else
			return null;
	}
	
	public void receiveWindowlog_receive(int seq) {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			writer.write("\n=======================================================\n");
			writer.write("收到seq为"+seq+"的包时\n");
			writer.write("=======================================================\n");
			writer.flush();		//清空输出缓存
			
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	
}
