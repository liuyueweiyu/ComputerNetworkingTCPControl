package com.ouc.tcp.test;

import com.ouc.tcp.message.TCP_PACKET;
import com.ouc.tcp.message.TCP_HEADER;
public class CheckSum {
	/*计算TCP报文段校验和：只需校验TCP首部中的seq、ack和sum，以及TCP数据字段*/
	public static short computeChkSum(TCP_PACKET tcpPack) {
		int checkSum = 0;
		TCP_HEADER header = tcpPack.getTcpH();
		int[] data = tcpPack.getTcpS().getData();
		int len = data.length,
			flag = 0xffff,
			flagmod = 65536;
		checkSum = header.getTh_ack();		//防止seq字段超过0xffff
		if(checkSum>flag)
			checkSum = checkSum%flagmod+checkSum/flagmod;
		checkSum +=header.getTh_seq();
		if(checkSum>flag)
			checkSum = checkSum%flagmod+checkSum/flagmod;
		for(int i = 0;i < len;i++) {
			checkSum += data[i];
			if(checkSum>flag)
				checkSum = checkSum%flagmod+checkSum/flagmod;
		}
		checkSum += header.getTh_sum();		
		if(checkSum>flag)
			checkSum = checkSum%flagmod+checkSum/flagmod;
		checkSum = ~checkSum;
		return (short) checkSum;
	}
	
}
