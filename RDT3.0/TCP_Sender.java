package com.ouc.tcp.test;

import java.io.IOException;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.client.UDT_RetransTask;
import com.ouc.tcp.client.UDT_Timer;
import com.ouc.tcp.message.*;
import com.ouc.tcp.tool.TCP_TOOL;


public class TCP_Sender extends TCP_Sender_ADT {
	
	private TCP_PACKET tcpPack;	//待发送的TCP数据报
	private UDT_Timer timer;	//用于做定时器
	private int sequence = 0;
	
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
		tcpH.setTh_sum((short)0);//先将校验码设为0，用于后续的计算
		tcpS.setData(appData);		
		tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);		
		//更新校验码；需要重新将tcpH填入到tcpPack				
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		sequence = tcpPack.getTcpH().getTh_seq();
		//发送TCP数据报
		udt_send(tcpPack);
		
		/**************************/
		/**定时器的用法：定时器到时后完成重传，需要用UDT_Timer用于计时；计时到0后，触发UDT_RetransTask完成重传**/
		timer = new UDT_Timer();
		/**重传器UDT_RetransTask将发送端和发送内容作为成员变量**/
		UDT_RetransTask reTrans = new UDT_RetransTask(client, tcpPack);
		/**UDT_Timer开始计时第一次重传为5s，以后每间隔3s完成一次重传；如果发现对方接收成功，需要在waitACK()中关闭计时器**/
		timer.schedule(reTrans, 5000, 300);
		
		//在waitACK使用无线循环和Break，来实现停止等待；当涉及Go-Back-N 或 Selective-Response的话，就不可以用停止等待了
		waitACK();
	
	}
	
	@Override
	//不可靠发送：将打包好的TCP数据报通过不可靠传输信道发送
	public void udt_send(TCP_PACKET tcpPack) {
		//设置错误控制标志
		tcpH.setTh_eflag((byte)7);
		
		//计算校验和，设置TCP首部重新打包
		
		//发送数据报
		client.send(tcpPack);
	}
	
	@Override
	//处理ACK报文，讲接收ACK与处理ACK分开
	public void waitACK() {
		//循环检查ackQueue;
		//使用无线循环和Break，来实现停止等待；当涉及Go-Back-N 或 Selective-Response的话，就不可以用停止等待了
		
		while(true) {
			if(!ackQueue.isEmpty() && ackQueue.poll() == tcpPack.getTcpH().getTh_seq()) {
				/**RDT3.0停止等待的时候需要关闭计时器**/
				timer.cancel();
				break;
			}
		}

	}

	@Override
	//接收到ACK报文：检查校验和，将确认号插入ack队列
	public void recv(TCP_PACKET recvPack) {
		//需要检查校验和
		if(sequence == recvPack.getTcpH().getTh_ack() && CheckSum.computeChkSum(recvPack) == 0 ) {
			//打印ACK号，便于调试
			System.out.println("Receive ACK Number： "+ recvPack.getTcpH().getTh_ack());
			//讲ACK号插入队列等待用WaitACK处理，将处理与接收回复分开
			ackQueue.add(recvPack.getTcpH().getTh_ack());
		}
	}
	
}
