### 计算机网络课程作业	实现TCP协议端到端的可靠传输

#### 实验须知

- 实验环境

  java 1.8.0_151

- 运行

  将根目录导入eclipse运行TestRun.java即可

- RDT3.0到选择响应所有文件变动较大，所以如果要运行RDT3.0，需要替换一下文件，文件放在了RDT3.0的文件夹中

- 选择响应传输和GBN传输的只要在的TCP_Sender.java中rdt_send调用对应send函数即可


#### 实验要求

1. 实现TCP协议端到端的可靠传输：数据包传送和确认，及对各种传输错误的处理；选择响应

2. 实现TCP_Sender类和TCP_Receiver类，进阶实现：RDT2.0 ->RDT2.2 -> RDT3.0 -> RDT4.0 选择响应协议

#### 实验原理

TCP进行可靠传输以保证数据包不会丢失、失序、重复并高效：

- 滑动窗口
- 超时重传
- 选择确认

![1543475127852](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543475127852.png)

![1543479194075](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543479194075.png)

#### 实验内容

1. RDT2.0

   - 假设：底层信道传输过程中，个别数据包的某些字节可能发生位错

   - 方法：使用校验和算法检查数据包的正确性（发送前计算/接收后校验）

   - 具体实现：

     - 校验和原理

       将发送的进行检验和运算的数据分成若干个16位的位串，每个位串看成一个二进制数，这里并不管字符串代表什么，是整数、浮点数还是位图都无所谓。

       并且将二进制数取反后相加，如果最高位发生进位采取**循环进位**

       同时**取反后相加结果等于相加后再取反**

     - 根据原理可以得出如下代码

       已知校验和16位，java中没有unsigned short，这里取int的低16位，不取short是因为short数据范围在-0x1000~0x7FFF，不好判断校验和是否大于0xFFFF，取int型较好判断校验和是否发生进位。并且在每次加上一个数字都需要判断是否大于0xFFFF，否则会出错。

       ```java
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
       		checkSum +=header.getTh_seq();		//防止seq字段超过0xffff
       		if(checkSum>flag)
       			checkSum = checkSum%flagmod+checkSum/flagmod;
       		for(int i = 0;i < len;i++) {
       			checkSum += data[i];
       			if(checkSum>flag)				//防止seq字段超过0xffff
       				checkSum = checkSum%flagmod+checkSum/flagmod;
       		}
       		checkSum += header.getTh_sum();		//防止seq字段超过0xffff
       		if(checkSum>flag)
       			checkSum = checkSum%flagmod+checkSum/flagmod;
       		checkSum = ~checkSum;
       		return (short) checkSum;
       	}
       	
       ```

     - 也可以使用java封装好的RCR

       ```java
       public static short computeChkSum(TCP_PACKET tcpPack) {
       	int checkSum = 0;
       	//计算校验和
       	TCP_HEADER tcpH = tcpPack.getTcpH();
       	CRC32 crc = new CRC32();
       	crc.update(tcpH.getTh_ack());
       	crc.update(tcpH.getTh_seq());
       	for(int data : tcpPack.getTcpS().getData()) {
       		crc.update(data);
       	}
       	checkSum = (int) crc.getValue();
       	return (short) checkSum;				
       }
       ```

   - 并且在接收方返回ACK包前加上判断

     ```java
     //重新计算校验和，如果没有发生错误校验和应为0
     if(CheckSum.computeChkSum(recvPack) == 0 ) {
         //生成返回报文
     	ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
     	tcpH.setTh_sum((short) 0);
     	tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
     	//回复ACK报文段
     	reply(ackPack);	
     	//有重复数据的情况下需要检查数据顺序号（确定是否接收了重复的数据）
     	//去除报文中的顺序号		
     	//判断是否是重复数据：非重复数据，将数据插入data队列
     	int[] data = recvPack.getTcpS().getData();	
     	dataQueue.add(data);
     	//更新期待接收的顺序号
     	sequence=sequence+data.length;
     }
     ```

   - 运行结果

     ![1543481796291](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543481796291.png)

     程序运行此处不再发送包，也不再接受包。

     查看日志可以看到

     ![1543481775825](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543481775825.png)

     编号5701为5701包出错，客户端检查出错。

   - RDT2.2

     对于返回的包添加ack标识，对于出错的包进行重传

     即在发送方和接收方都设置一个字段来设置当前传输的包，若当前的传输的包出错则不响应，发送方设置计时器，在一定时间内没有收到接收方设置的ACK包则进行重发，同时如果接收方发送的ACK包也出错的话视为接收方未响应并重发

     ```java
     //发送端
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
     ```

     接收端

     此时需要注意，在接收端无论收到的包都需要的回复，因为有可能是上一个已经确认过的包回复ACK包出错，但是此时不需要交互

     ```java
     public void rdt_recv(TCP_PACKET recvPack) {
     	//检查校验码，生成ACK
     	int seq = recvPack.getTcpH().getTh_seq();
     	if(CheckSum.computeChkSum(recvPack) == 0 ) {
     		//生成ACK报文段（设置确认号）
     		tcpH.setTh_ack(recvPack.getTcpH().getTh_seq());
     		ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
     		tcpH.setTh_sum((short) 0);
     		tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
     		//回复ACK报文段
     		reply(ackPack);	
             
     		//有重复数据的情况下需要检查数据顺序号（确定是否接收了重复的数据）
     		if(sequence == seq) {
     			//判断是否是重复数据：非重复数据，将数据插入data队列
     			int[] data = recvPack.getTcpS().getData();	
     			dataQueue.add(data);
     			//更新期待接收的顺序号
     			sequence=sequence+data.length;
     		}
     	}
     	if(dataQueue.size() >= 20) 
     		deliver_data();	
     }
     ```

     运行结果

     出错的包超时重传

     ![1543483362057](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543483362057.png)

     回复ack的包出错重传

     发送方并未出错

     ![1543483489261](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543483489261.png)

     ![1543483522574](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543483522574.png)

   - RDT3.0

     通道上可能出错和丢失数据

     丢失数据方法也同上，设置定时器当丢失或延迟的超出一定时间内，便重新发送

     运行结果

     - 延迟

       ![1543483965620](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543483965620.png)

     - 丢包

       ![1543484026183](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543484026183.png)

   - 选择响应

     上述实验都是在当前最多只能发一个包的情况下进行的，效率非常低，所以对上诉实验进行完善

     1. 构建Window类

        同时发送多个包，此时需要构建滑动窗口，基本属性

        ```java
        public class Window {
        	public int windowSize = 10;	//窗口大小
        	public Client client;		//客户端
            //窗口缓存区域
        	public TCP_PACKET[] packets = new TCP_PACKET[windowSize];
            //缓存区域为循环队列所以需要三个指针
            //begin起始指针，now当前发送位置指针，end终指针
        	public volatile int begin = 0,now = 0, end = windowSize - 1;
            //当前所期待的包的sequence值，即begin指针指向的包的sequence值
        	public int sequence = 1;
            //缓存区的包所确认情况的确认区域
        	public boolean[] checkAck = new boolean[windowSize];
        	/*构造函数*/
        	public Window(Client client) {
        		super();
        		this.client = client;
        	}		
        }
        
        ```

     2. 在根据发送方和接收方的不同，分别构建各自的窗口

        发送方窗口

        ```java
        public class SendWindow extends Window {
            //增加缓存属性对应的计时器数组
        	private UDT_Timer[] timers = new UDT_Timer[windowSize];
        	/*构造函数*/
        	public SendWindow(Client client) {}		
        	//通过选择响应的方式的发包
        	public void sendPacket_select(TCP_PACKET packet) {}
        	//接收到ack包对窗口操作，查看是否应该滑动窗口
        	public void  ackPacket(TCP_PACKET packet) {}
        	//判断窗口是否满
        	public boolean  isFull() {}
        }
        ```

        接收方窗口

        ```java
        public class ReceiveWindow extends Window {
        	//构造函数
        	public ReceiveWindow(Client client) {}
        	//接收到发送包发来的包后进行回复
            //recvPack:回复的包
            //packet:收到的包
        	public Vector reply(TCP_PACKET recvPack,TCP_PACKET packet) {}
        }
        ```

     3. 具体实现

        ```java
        //TCP_Sender.java
        public void rdt_send(int dataIndex, int[] appData) {
        	//产生数据报...
            //判断窗口是否已满
        	while(window.isFull());
        	try {
                //发送TCP数据报，注意这里发送的必须是packet的副本
                //因为在直接赋值的话，是浅拷贝，赋值引用，在后续发送中会出错
        		TCP_PACKET packet = new TCP_PACKET(tcpH.clone(), tcpS.clone(), destinAddr);	
        		window.sendPacket_select(packet);
        	} catch (CloneNotSupportedException e) {
        		// TODO Auto-generated catch block
        		e.printStackTrace();
        	}
        }
        	
        ```

        ```java
        //SendWindow.java
        public void sendPacket_select(TCP_PACKET packet) {	//向窗口中加入新包
            //这里涉及循环队列的设计
            //循环队列的三个指针只要自加%windowSize就是窗口对应的下标
        	int index = now%windowSize;
            packets[index] = packet;	//把包赋值至缓存区
            checkAck[index] = false;	//将包的确认情况设置为未确认
        	timers[index] = new UDT_Timer();	//给包新增计时器
        	UDT_RetransTask reTrans = new UDT_RetransTask(client, packet);
        	timers[index].schedule(reTrans, 5000, 1500);
        	now++;	//移动now指针
        	client.send(packet);	//发送包
        }
        ```

        ```java
        //TCP_Receiver.java
        public void rdt_recv(TCP_PACKET recvPack) {
        	//检查校验码，生成ACK
        	if(CheckSum.computeChkSum(recvPack) != 0)
        		return;
            //...
        	//回复ACK报文段
        	Vector data = window.reply(recvPack,ackPack);
        	//data不为nulll意味着窗口首的数据满了，可以交付数据
        	if( data != null) {
        		for(int i = 0;i<data.size();i++) {
        			dataQueue.add((int[]) data.get(i));
        		}
        	deliver_data();	
        	}
        }
        ```

        ```java
        //ReceiveWindow.java
        public Vector reply(TCP_PACKET recvPack,TCP_PACKET packet) {
        	Vector result = new Vector();
            //收到的包的下标
        	int index = packet.getTcpH().getTh_ack()/100;
            //排除收到的是之前延迟过了很久才到的包的情况
        	if(index >= begin ) {
                //收到的包对应的缓存区的下标
        		index = index % windowSize;
        		checkAck[index] = true;	//设置已经确认
        		packets[index] = recvPack;	//将收到的包放入缓存区
        		client.send(packet);	//发送ack包
                //当前确认的包为窗口的第一个值时开始滑动窗口
        		if(index == begin%windowSize) {		
        			int j = begin;
        			for(;j<=end&&checkAck[j%windowSize];j++) {
                        //滑动的同时将信息清空
        				int jdenx = j%windowSize;
        				System.out.println(jdenx);
        				result.addElement(packets[jdenx].getTcpS().getData());
        				checkAck[jdenx] = false;
        			}
                    //确认滑动终点
        			begin = j ;
        			end = begin + windowSize - 1;
        			sequence = packets[(begin1)%windowSize].getTcpH().getTh_seq();
        			//并且交付数据
                    return result;
        		}
        	}
        	return null;
        }
        ```

        ```java
        //TCP_Sender.java
        public void recv(TCP_PACKET recvPack) {
        	//需要检查校验和
        	if(CheckSum.computeChkSum(recvPack) != 0)
        		return;
            //无误之后回复ack包
        	window.ackPacket(recvPack);
        }
        ```

        ```java
        //SendWindow.java
        public void  ackPacket(TCP_PACKET packet) {	//收到ack包后将返回的包确认
            //收到的包对应的缓存区的下标
        	int i = packet.getTcpH().getTh_ack()/100;
            //排除收到的是之前延迟过了很久才到的包的情况
        	if(i >= 0) {
        		int index = i%windowSize;
                //窗口最初有可能是空指针的情况
        		if(packets[index]!= null) {	
        			timers[index].cancel();	//收到包之后该取消计时器
        			checkAck[index] = true;	//设置已经确认
        			if(i == begin) {	//当接到的包是窗口第一个值时,开始滑动窗口
        				int j = begin;
        				for(;j<= now && checkAck[j%windowSize];j++) {
                            //滑动的同时将信息清空
        					packets[j%windowSize] = null;
        					checkAck[j%windowSize] = false;
        					timers[index] = null;
        				}
                        //确认滑动终点
        				begin = Math.min(j,now);
        				end = begin + windowSize -1;
        				sequence = begin * 100 + 1;
        			}
        		}
        	}
        }
        ```

     4. 运行结果

        ![1543492404019](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543492404019.png)

        当2801号包出错之后，窗口滑动至2801号窗口不再滑动，计时器时间到后重新发送，发送后窗口直接滑动至窗口尾即3601后，即开始发送3701号包

        本次发送情况为

        ![1543492598614](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543492598614.png)

        发送错误点部分情况为：

        ```
        ...
        	2018-11-29 19:12:41:755 CST	DATA_seq: 2801	WRONG	NO_ACK
        	2018-11-29 19:12:41:778 CST	DATA_seq: 2901		ACKed
        	2018-11-29 19:12:41:794 CST	DATA_seq: 3001		ACKed
        	2018-11-29 19:12:41:812 CST	DATA_seq: 3101		ACKed
        	2018-11-29 19:12:41:835 CST	DATA_seq: 3201		ACKed
        	2018-11-29 19:12:41:850 CST	DATA_seq: 3301		ACKed
        	2018-11-29 19:12:41:879 CST	DATA_seq: 3401		ACKed
        	2018-11-29 19:12:41:924 CST	DATA_seq: 3501		ACKed
        	2018-11-29 19:12:41:939 CST	DATA_seq: 3601		ACKed
        	2018-11-29 19:12:46:746 CST	*Re: DATA_seq: 2801		ACKed
        	2018-11-29 19:12:46:751 CST	DATA_seq: 3701		ACKed
        	2018-11-29 19:12:46:765 CST	DATA_seq: 3801		ACKed
        ...
        ...
        	2018-11-29 19:12:47:739 CST	DATA_seq: 7701		ACKed
        	2018-11-29 19:12:47:769 CST	DATA_seq: 7801		NO_ACK
        	2018-11-29 19:12:47:806 CST	DATA_seq: 7901		ACKed
        	2018-11-29 19:12:47:824 CST	DATA_seq: 8001		ACKed
        	2018-11-29 19:12:47:865 CST	DATA_seq: 8101		ACKed
        	2018-11-29 19:12:47:885 CST	DATA_seq: 8201		ACKed
        	2018-11-29 19:12:47:899 CST	DATA_seq: 8301		ACKed
        	2018-11-29 19:12:47:914 CST	DATA_seq: 8401		ACKed
        	2018-11-29 19:12:47:932 CST	DATA_seq: 8501		ACKed
        	2018-11-29 19:12:47:953 CST	DATA_seq: 8601		ACKed
        	2018-11-29 19:12:52:768 CST	*Re: DATA_seq: 7801		ACKed
        	2018-11-29 19:12:52:771 CST	DATA_seq: 8701		ACKed
        	2018-11-29 19:12:52:784 CST	DATA_seq: 8801		ACKed
        	2018-11-29 19:12:52:796 CST	DATA_seq: 8901		ACKed
        ...
        ...
        	2018-11-29 19:12:53:040 CST	DATA_seq: 10601		ACKed
        	2018-11-29 19:12:53:053 CST	DATA_seq: 10701		ACKed
        	2018-11-29 19:12:53:064 CST	DATA_seq: 10801	LOSS	NO_ACK
        	2018-11-29 19:12:53:076 CST	DATA_seq: 10901		ACKed
        	2018-11-29 19:12:53:089 CST	DATA_seq: 11001		NO_ACK
        	2018-11-29 19:12:53:102 CST	DATA_seq: 11101		ACKed
        	2018-11-29 19:12:53:115 CST	DATA_seq: 11201		ACKed
        	2018-11-29 19:12:53:140 CST	DATA_seq: 11301		ACKed
        	2018-11-29 19:12:53:152 CST	DATA_seq: 11401		ACKed
        	2018-11-29 19:12:53:163 CST	DATA_seq: 11501		ACKed
        	2018-11-29 19:12:53:180 CST	DATA_seq: 11601		ACKed
        	2018-11-29 19:12:58:065 CST	*Re: DATA_seq: 10801		ACKed
        	2018-11-29 19:12:58:069 CST	DATA_seq: 11701		ACKed
        	2018-11-29 19:12:58:085 CST	DATA_seq: 11801		ACKed
        	2018-11-29 19:12:58:090 CST	*Re: DATA_seq: 11001	LOSS	NO_ACK
        	2018-11-29 19:12:59:590 CST	*Re: DATA_seq: 11001		ACKed
        	2018-11-29 19:12:59:594 CST	DATA_seq: 11901		ACKed
        	2018-11-29 19:12:59:612 CST	DATA_seq: 12001		ACKed
        	2018-11-29 19:12:59:627 CST	DATA_seq: 12101		ACKed
        	2018-11-29 19:12:59:657 CST	DATA_seq: 12201		ACKed
        ...
        ...
        	2018-11-29 19:13:00:145 CST	DATA_seq: 14701	LOSS	NO_ACK
        	2018-11-29 19:13:00:199 CST	DATA_seq: 14801		ACKed
        	2018-11-29 19:13:00:214 CST	DATA_seq: 14901		ACKed
        	2018-11-29 19:13:00:242 CST	DATA_seq: 15001		ACKed
        	2018-11-29 19:13:00:254 CST	DATA_seq: 15101		ACKed
        	2018-11-29 19:13:00:267 CST	DATA_seq: 15201		ACKed
        	2018-11-29 19:13:00:287 CST	DATA_seq: 15301		ACKed
        	2018-11-29 19:13:00:301 CST	DATA_seq: 15401		ACKed
        	2018-11-29 19:13:00:315 CST	DATA_seq: 15501	DELAY	NO_ACK
        	2018-11-29 19:13:05:146 CST	*Re: DATA_seq: 14701		ACKed
        	2018-11-29 19:13:05:149 CST	DATA_seq: 15601		ACKed
        	2018-11-29 19:13:05:161 CST	DATA_seq: 15701		ACKed
        	2018-11-29 19:13:05:173 CST	DATA_seq: 15801		ACKed
        	2018-11-29 19:13:05:186 CST	DATA_seq: 15901		ACKed
        	2018-11-29 19:13:05:197 CST	DATA_seq: 16001		ACKed
        	2018-11-29 19:13:05:215 CST	DATA_seq: 16101		ACKed
        	2018-11-29 19:13:05:229 CST	DATA_seq: 16201		ACKed
        	2018-11-29 19:13:05:242 CST	DATA_seq: 16301		ACKed
        	2018-11-29 19:13:05:340 CST	*Re: DATA_seq: 15501		ACKed
        	2018-11-29 19:13:05:358 CST	DATA_seq: 16401		ACKed
        ...
        ...
        	2018-11-29 19:13:06:470 CST	DATA_seq: 20901		ACKed
        	2018-11-29 19:13:06:489 CST	DATA_seq: 21001		ACKed
        	2018-11-29 19:13:06:509 CST	DATA_seq: 21101		NO_ACK
        	2018-11-29 19:13:06:532 CST	DATA_seq: 21201		ACKed
        	2018-11-29 19:13:06:555 CST	DATA_seq: 21301		ACKed
        	2018-11-29 19:13:06:574 CST	DATA_seq: 21401		ACKed
        	2018-11-29 19:13:06:597 CST	DATA_seq: 21501		ACKed
        	2018-11-29 19:13:06:615 CST	DATA_seq: 21601		ACKed
        	2018-11-29 19:13:06:634 CST	DATA_seq: 21701		ACKed
        	2018-11-29 19:13:06:653 CST	DATA_seq: 21801		ACKed
        	2018-11-29 19:13:06:682 CST	DATA_seq: 21901		NO_ACK
        	2018-11-29 19:13:11:510 CST	*Re: DATA_seq: 21101		ACKed
        	2018-11-29 19:13:11:512 CST	DATA_seq: 22001		ACKed
        	2018-11-29 19:13:11:523 CST	DATA_seq: 22101		ACKed
        	2018-11-29 19:13:11:535 CST	DATA_seq: 22201		ACKed
        	2018-11-29 19:13:11:548 CST	DATA_seq: 22301		ACKed
        	2018-11-29 19:13:11:561 CST	DATA_seq: 22401		ACKed
        	2018-11-29 19:13:11:574 CST	DATA_seq: 22501		ACKed
        	2018-11-29 19:13:11:586 CST	DATA_seq: 22601		ACKed
        	2018-11-29 19:13:11:598 CST	DATA_seq: 22701		ACKed
        	2018-11-29 19:13:11:682 CST	*Re: DATA_seq: 21901	WRONG	NO_ACK
        	2018-11-29 19:13:13:182 CST	*Re: DATA_seq: 21901		ACKed
        	2018-11-29 19:13:13:185 CST	DATA_seq: 22801		ACKed
        	2018-11-29 19:13:13:205 CST	DATA_seq: 22901		ACKed
        	2018-11-29 19:13:13:230 CST	DATA_seq: 23001		ACKed
        ...
        ...
        	2018-11-29 19:13:14:688 CST	DATA_seq: 30401		ACKed
        	2018-11-29 19:13:14:708 CST	DATA_seq: 30501	LOSS	NO_ACK
        	2018-11-29 19:13:14:730 CST	DATA_seq: 30601		ACKed
        	2018-11-29 19:13:14:761 CST	DATA_seq: 30701		ACKed
        	2018-11-29 19:13:14:787 CST	DATA_seq: 30801		ACKed
        	2018-11-29 19:13:14:803 CST	DATA_seq: 30901		ACKed
        	2018-11-29 19:13:14:835 CST	DATA_seq: 31001		ACKed
        	2018-11-29 19:13:14:850 CST	DATA_seq: 31101		ACKed
        	2018-11-29 19:13:14:885 CST	DATA_seq: 31201		ACKed
        	2018-11-29 19:13:14:941 CST	DATA_seq: 31301		ACKed
        	2018-11-29 19:13:19:708 CST	*Re: DATA_seq: 30501		ACKed
        	2018-11-29 19:13:19:710 CST	DATA_seq: 31401		ACKed
        	2018-11-29 19:13:19:722 CST	DATA_seq: 31501		ACKed
        	2018-11-29 19:13:19:735 CST	DATA_seq: 31601		ACKed
        	2018-11-29 19:13:19:746 CST	DATA_seq: 31701		ACKed
        ...
        ...
        ...
        ```

   - GBN

     关于go-back-n只有发送窗口的关于超时的时候所进行的操作不同

     所以，要重写TimerTask的run函数已达到重发当前窗口之后所以的包的目的

     ```java
     public void run() {
         //重发当前窗口所有的包
     	for (int i=begin;i<= now;i++) {
     		int index = i%windowSize;
     		if(packets[index]!=null) {
     			try {
     				client.send(packets[index].clone());
     			} catch (CloneNotSupportedException e) {
     				// TODO Auto-generated catch block
     				e.printStackTrace();
     			}	
     		}		
     	}
     }
     ```

     然后只要将sendPacket_select函数做以下修改即可

     ```java
     public void sendPacket_GBN(TCP_PACKET packet) throws CloneNotSupportedException {	//向窗口中加入新包
     	//在窗口的idnex
     	int index = now%windowSize;
     	packets[index] = packet;
     	checkAck[index] = false;
     	timers[index] = new UDT_Timer();
         //修改部分
     	TaskPacketsRetrans reTrans = new TaskPacketsRetrans();
     	timers[index].schedule(reTrans, 5000, 1500);
     	setNowLog(packet.getTcpH().getTh_seq());
     	now++;
     	client.send(packet);
     }
     ```

     运行结果

     可以看到当前53401号没有响应后，在计时器到时后，将当前的包全部重发了

     ![1543493994705](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543493994705.png)

     本次发送情况为

     ![1543494085825](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543494085825.png)

     发送错误点部分情况为：

     ```
     ...
     	2018-11-29 20:10:02:327 CST	DATA_seq: 1501		ACKed
     	2018-11-29 20:10:02:355 CST	DATA_seq: 1601		ACKed
     	2018-11-29 20:10:02:391 CST	DATA_seq: 1701	LOSS	NO_ACK
     	2018-11-29 20:10:02:411 CST	DATA_seq: 1801		ACKed
     	2018-11-29 20:10:02:426 CST	DATA_seq: 1901		ACKed
     	2018-11-29 20:10:02:444 CST	DATA_seq: 2001		ACKed
     	2018-11-29 20:10:02:470 CST	DATA_seq: 2101		ACKed
     	2018-11-29 20:10:02:499 CST	DATA_seq: 2201		ACKed
     	2018-11-29 20:10:02:527 CST	DATA_seq: 2301		ACKed
     	2018-11-29 20:10:02:551 CST	DATA_seq: 2401	DELAY	NO_ACK
     	2018-11-29 20:10:02:584 CST	DATA_seq: 2501		ACKed
     	2018-11-29 20:10:07:378 CST	*Re: DATA_seq: 1701		ACKed
     	2018-11-29 20:10:07:379 CST	DATA_seq: 1801		ACKed
     	2018-11-29 20:10:07:382 CST	DATA_seq: 1901		ACKed
     	2018-11-29 20:10:07:383 CST	DATA_seq: 2001		ACKed
     	2018-11-29 20:10:07:383 CST	DATA_seq: 2101		ACKed
     	2018-11-29 20:10:07:384 CST	DATA_seq: 2201		ACKed
     	2018-11-29 20:10:07:384 CST	DATA_seq: 2301		ACKed
     	2018-11-29 20:10:07:385 CST	*Re: DATA_seq: 2401		ACKed
     	2018-11-29 20:10:07:385 CST	DATA_seq: 2501		ACKed
     	2018-11-29 20:10:07:400 CST	DATA_seq: 2601		ACKed
     	2018-11-29 20:10:07:431 CST	DATA_seq: 2701		ACKed
     	2018-11-29 20:10:07:472 CST	DATA_seq: 2801		ACKed
     	2018-11-29 20:10:07:544 CST	DATA_seq: 2401		ACKed
     	2018-11-29 20:10:07:544 CST	DATA_seq: 2501		ACKed
     	2018-11-29 20:10:07:545 CST	DATA_seq: 2601		ACKed
     	2018-11-29 20:10:07:545 CST	DATA_seq: 2701		ACKed
     	2018-11-29 20:10:07:546 CST	DATA_seq: 2801		ACKed
     	2018-11-29 20:10:07:547 CST	DATA_seq: 2901		ACKed
     	2018-11-29 20:10:07:617 CST	DATA_seq: 3001		ACKed
     ...
     ...
     2018-11-29 20:10:15:440 CST	DATA_seq: 12901		ACKed
     	2018-11-29 20:10:15:458 CST	DATA_seq: 13001		ACKed
     	2018-11-29 20:10:15:476 CST	DATA_seq: 13101		ACKed
     	2018-11-29 20:10:20:302 CST	*Re: DATA_seq: 12301		ACKed
     	2018-11-29 20:10:20:302 CST	DATA_seq: 12401		ACKed
     	2018-11-29 20:10:20:302 CST	DATA_seq: 12501		ACKed
     	2018-11-29 20:10:20:303 CST	DATA_seq: 12601		ACKed
     	2018-11-29 20:10:20:303 CST	DATA_seq: 12701		ACKed
     	2018-11-29 20:10:20:303 CST	DATA_seq: 12801		ACKed
     	2018-11-29 20:10:20:305 CST	DATA_seq: 12901		ACKed
     	2018-11-29 20:10:20:306 CST	DATA_seq: 13201		ACKed
     	2018-11-29 20:10:20:326 CST	*Re: DATA_seq: 13201		ACKed
     	2018-11-29 20:10:20:341 CST	DATA_seq: 13301		ACKed
     	2018-11-29 20:10:20:378 CST	DATA_seq: 13401		ACKed
     	2018-11-29 20:10:20:577 CST	DATA_seq: 13501		ACKed
     	2018-11-29 20:10:20:688 CST	DATA_seq: 13601		ACKed
     	2018-11-29 20:10:20:823 CST	DATA_seq: 13701		ACKed
     	2018-11-29 20:10:20:886 CST	DATA_seq: 13801		ACKed
     	2018-11-29 20:10:20:941 CST	DATA_seq: 13901		ACKed
     	2018-11-29 20:10:21:036 CST	DATA_seq: 14001		ACKed
     	2018-11-29 20:10:21:059 CST	DATA_seq: 14101		ACKed
     	2018-11-29 20:10:21:086 CST	DATA_seq: 14201		ACKed
     	2018-11-29 20:10:21:158 CST	DATA_seq: 14301		NO_ACK
     	2018-11-29 20:10:21:415 CST	DATA_seq: 14401		ACKed
     	2018-11-29 20:10:21:441 CST	DATA_seq: 14501		ACKed
     	2018-11-29 20:10:21:463 CST	DATA_seq: 14601		ACKed
     	2018-11-29 20:10:21:485 CST	DATA_seq: 14701		ACKed
     	2018-11-29 20:10:21:507 CST	DATA_seq: 14801		ACKed
     	2018-11-29 20:10:21:539 CST	DATA_seq: 14901		ACKed
     	2018-11-29 20:10:21:568 CST	DATA_seq: 15001		ACKed
     	2018-11-29 20:10:21:601 CST	DATA_seq: 15101		ACKed
     	2018-11-29 20:10:26:120 CST	*Re: DATA_seq: 14301		ACKed
     	2018-11-29 20:10:26:120 CST	DATA_seq: 14401		ACKed
     	2018-11-29 20:10:26:120 CST	DATA_seq: 14501		ACKed
     	2018-11-29 20:10:26:121 CST	DATA_seq: 14601		ACKed
     	2018-11-29 20:10:26:121 CST	DATA_seq: 14701		ACKed
     	2018-11-29 20:10:26:121 CST	DATA_seq: 14801		ACKed
     	2018-11-29 20:10:26:121 CST	DATA_seq: 14901		ACKed
     	2018-11-29 20:10:26:121 CST	DATA_seq: 15001		ACKed
     	2018-11-29 20:10:26:121 CST	DATA_seq: 15101		ACKed
     ...
     ...
     	2018-11-29 20:10:44:712 CST	DATA_seq: 34101	DELAY	NO_ACK
     	2018-11-29 20:10:44:730 CST	DATA_seq: 34201		ACKed
     	2018-11-29 20:10:44:749 CST	DATA_seq: 34301		ACKed
     	2018-11-29 20:10:44:768 CST	DATA_seq: 34401		ACKed
     	2018-11-29 20:10:44:790 CST	DATA_seq: 34501		ACKed
     	2018-11-29 20:10:44:808 CST	DATA_seq: 34601		ACKed
     	2018-11-29 20:10:44:828 CST	DATA_seq: 34701		ACKed
     	2018-11-29 20:10:44:848 CST	DATA_seq: 34801		ACKed
     	2018-11-29 20:10:44:866 CST	DATA_seq: 34901		ACKed
     	2018-11-29 20:10:49:702 CST	*Re: DATA_seq: 34101		ACKed
     	2018-11-29 20:10:49:702 CST	DATA_seq: 34201		ACKed
     	2018-11-29 20:10:49:702 CST	DATA_seq: 34301		NO_ACK
     	2018-11-29 20:10:49:702 CST	DATA_seq: 34401		ACKed
     	2018-11-29 20:10:49:703 CST	DATA_seq: 34501		ACKed
     	2018-11-29 20:10:49:703 CST	DATA_seq: 34601		ACKed
     	2018-11-29 20:10:49:703 CST	DATA_seq: 34701		ACKed
     	2018-11-29 20:10:49:703 CST	DATA_seq: 34801		ACKed
     	2018-11-29 20:10:49:703 CST	DATA_seq: 34901		ACKed
     	2018-11-29 20:10:49:715 CST	DATA_seq: 35001		ACKed
     	2018-11-29 20:10:49:736 CST	DATA_seq: 35101		ACKed
     	2018-11-29 20:10:49:761 CST	DATA_seq: 35201		ACKed
     	2018-11-29 20:10:49:785 CST	DATA_seq: 35301	DELAY	NO_ACK
     	2018-11-29 20:10:49:808 CST	DATA_seq: 35401		ACKed
     	2018-11-29 20:10:49:835 CST	DATA_seq: 35501		ACKed
     	2018-11-29 20:10:49:856 CST	DATA_seq: 35601		ACKed
     	2018-11-29 20:10:49:878 CST	DATA_seq: 35701		ACKed
     	2018-11-29 20:10:49:901 CST	DATA_seq: 35801		ACKed
     	2018-11-29 20:10:49:923 CST	DATA_seq: 35901		ACKed
     	2018-11-29 20:10:49:946 CST	DATA_seq: 36001		ACKed
     	2018-11-29 20:10:49:969 CST	DATA_seq: 36101		ACKed
     	2018-11-29 20:10:54:773 CST	*Re: DATA_seq: 35301		ACKed
     	2018-11-29 20:10:54:773 CST	DATA_seq: 35401		ACKed
     	2018-11-29 20:10:54:773 CST	DATA_seq: 35501		ACKed
     	2018-11-29 20:10:54:773 CST	DATA_seq: 35601		ACKed
     	2018-11-29 20:10:54:773 CST	DATA_seq: 35701	LOSS	NO_ACK
     	2018-11-29 20:10:54:773 CST	DATA_seq: 35801		ACKed
     	2018-11-29 20:10:54:774 CST	DATA_seq: 35901		ACKed
     	2018-11-29 20:10:54:774 CST	DATA_seq: 36001		ACKed
     	2018-11-29 20:10:54:774 CST	DATA_seq: 36101		ACKed
     	2018-11-29 20:10:54:785 CST	DATA_seq: 36201		ACKed
     	2018-11-29 20:10:54:807 CST	DATA_seq: 36301		ACKed
     	2018-11-29 20:10:54:830 CST	DATA_seq: 36401		ACKed
     	2018-11-29 20:10:54:852 CST	DATA_seq: 36501		ACKed
     	2018-11-29 20:10:54:872 CST	DATA_seq: 36601		ACKed
     	2018-11-29 20:10:54:895 CST	DATA_seq: 36701		ACKed
     	2018-11-29 20:10:54:921 CST	DATA_seq: 36801		ACKed
     ...
     ```

#### 实验总结

1. 在这次实验中可以说是碰到很多困难了，最明显的两个问题

   1. 引用问题：

      就是在将包放入缓冲区

      ```java
      //最开始的写法
      //TCP_Sender.java
      window.sendPacket_GBN(packet.clone());
      //ReceiveWindow.java
      packets[index] = packet;	//将包放入缓存区
      ```

      这样看起来是没有问题，但是在运行过程中，还是有问题，就是在出现丢包，或者位错之后重发的包是之前的包，并不是对于号码的包。

      原因是TCP_PACKET类中仍然包括了其他的对象，虽然对于packet的clone是深拷贝，但是对于对象里面内嵌的对象仍然是拷贝其引用。故在重发的时候会重发原来的包的原因是便是包本身的引用还是本身的，但是header和segment的引用还是旧的，所以出现这个问题。

      ![1543495285994](https://raw.githubusercontent.com/liuyueweiyu/ComputerNetworkingHomework/master/images/1543495285994.png)

   2. 如果说上面的那个问题的让人感觉窒息的话，那么这个问题真是让人~~缩不粗发~~说不出话。

      在编写程序的时候为了方便调试，在判断窗口是否满的时候，我输出了三个指针的下标。

      ```java
      public boolean  isFull() {
      	System.out.println("begin:"+begin);
      	System.out.println("now:"+now);
      	System.out.println("end:"+end);
      	return now == end;
      }
      ```
      在最后整理代码的时候我把这三行输出去掉了。然后，这个程序，就

      出错了。

      错了。

      了。

      。

      我判断窗口满，当不满的时候才能接着运行设置了这么一个循环

      ```java
      while(window.isFull());
      ```

      就是只要在传输包的过程中只要的出错，丢包，延迟，就跳不出这个循环。

      我尝试了去掉注释后，然后又能正常运行了。

      然后。尝试不输出的时候而是将线程挂起，又可以正常运行了。

      ```java
      public boolean  isFull() {
      	try {
      		Thread.sleep(1);
      	} catch (InterruptedException e) {
      		// TODO Auto-generated catch block
      		e.printStackTrace();
      	}
      	return now == end;
      }
      ```

      这。就。很神奇了。

      解释情况大概是：

      JVM在多线程的情况下，会将资源拷贝副本至多个线程中，在运行过程中最先修改的是当前线程拷贝的副本，然后在去掉注释或者是没有挂起的时候，now和end的值在不同线程中是数据不一致的，也就是在now和end被修改了但是没有和其他线程的now和end同步，所以，只要让now和end在不同线程中数据保持一致就可以正常运行了，至于为什么输出和挂起可以让多个线程数据保持一致，这个我就不懂了，我猜是当前线程一直在获取now和end，所以没办法同步，挂起和的输出让JVM有空闲去同步now和end。

      所以解决方案：

      ```java
      public volatile int begin = 0,now = 0, end = windowSize - 1;
      ```

      使用volatile变量

2. 还有就是日志的使用非常重要。

   没有日志看控制台真是太感人了。

   虽然控制台的内容页可以导出文件，但是看的也是太痛苦了。

   中间还自己写了输出日志。

   多线程这玩意下个断点这个线程的的停住了，其他线程不会停真是太难调试了。

   靠控制台输出真是太绝望了。

3. ~~说好的要求java1.6表示1.8也顺利写完~~

4. ~~写java是不可能的，这辈子都不可能的~~

5. ~~多线程是不可能的，这辈子都不可能的~~

