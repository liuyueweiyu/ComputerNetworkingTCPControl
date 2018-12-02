package com.ouc.tcp.test;


import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;


public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	SendWindow window = new SendWindow(client);	//实例化窗口
	
	/*构造函数*/
	public TCP_Sender() {
		super();	//调用超类构造函数
		super.initTCP_Sender(this);		//初始化TCP发送端
	}
	
	@Override
	//可靠发送（应用层调用）：封装应用层数据，产生TCP数据报
	public void rdt_send(int dataIndex, int[] appData) {
		//生成TCP数据报（设置序号和数据字段/校验和),注意打包的顺序
		tcpH.setTh_seq(dataIndex * appData.length + 1);//包序号设置为字节流号：你也可以使用其他编号方式，注意修改对应的接收方判断序号的部分
		tcpH.setTh_eflag((byte)7);
		tcpH.setTh_sum((short)0);//先将校验码设为0，用于后续的计算
		tcpS.setData(appData);		
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
		//更新校验码；需要重新将tcpH填入到tcpPack				
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		//发送TCP数据报
		while(window.isFull());
		try {
			TCP_PACKET packet = new TCP_PACKET(tcpH.clone(), tcpS.clone(), destinAddr);	
			window.sendPacket_GBN(packet);
		} catch (CloneNotSupportedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	

	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送
	public void udt_send(TCP_PACKET tcpPack) {
	}
	
	@Override
	//处理ACK报文，讲接收ACK与处理ACK分开
	public void waitACK() {
		//循环检查ackQueue;


	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列
	public void recv(TCP_PACKET recvPack) {
		//需要检查校验和
		if(CheckSum.computeChkSum(recvPack) != 0)
			return;
		window.ackPacket_GBN(recvPack);
	}
	
}
