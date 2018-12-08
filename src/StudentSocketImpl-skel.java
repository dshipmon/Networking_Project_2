import java.net.*;
import java.io.*;
import java.util.*;

class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private Demultiplexer D;
  private Timer tcpTimer;
  private int state;
  private int seqNum;
  private int ackNum;
  private Hashtable<Integer, TCPTimerTask> timerList; //holds the timers for sent packets
  private LinkedHashMap<Integer, TCPPacket> packetList;	  //holds the packets associated with each timer
  //(for timer identification)
  private boolean wantsToClose = false;
  private boolean finSent = false;

  private static final int CLOSED = 0;
  private static final int SYN_SENT = 1;
  private static final int LISTEN = 2;
  private static final int SYN_RCVD = 3;
  private static final int ESTABLISHED = 4;
  private static final int FIN_WAIT_1 = 5;
  private static final int FIN_WAIT_2 = 6;
  private static final int CLOSING = 7;
  private static final int CLOSE_WAIT = 8;
  private static final int LAST_ACK = 9;
  private static final int TIME_WAIT = 10;

  private PipedOutputStream appOS;
  private PipedInputStream appIS;

  private PipedInputStream pipeAppToSocket;
  private PipedOutputStream pipeSocketToApp;

  private SocketReader reader;
  private SocketWriter writer;

  private boolean terminating = false;

  private InfiniteBuffer sendBuffer;
  private InfiniteBuffer recvBuffer;
  private int sendbufferDataLength = 0;
  private int dataPacketSize = 1000;
  private TCPPacket prevPacket = null;
  private int base;

  StudentSocketImpl(Demultiplexer D) {  // default constructor
    this.D = D;
    state = CLOSED;
    seqNum = -1;
    ackNum = -1;
    timerList = new Hashtable<>();
    packetList = new LinkedHashMap<>();

    try {
      pipeAppToSocket = new PipedInputStream();
      pipeSocketToApp = new PipedOutputStream();

      appIS = new PipedInputStream(pipeSocketToApp);
      appOS = new PipedOutputStream(pipeAppToSocket);
    }
    catch(IOException e){
      System.err.println("unable to create piped sockets");
      System.exit(1);
    }


    initBuffers();

    reader = new SocketReader(pipeAppToSocket, this);
    reader.start();

    writer = new SocketWriter(pipeSocketToApp, this);
    writer.start();
  }

  private String stateString(int inState){
    if(inState == 0){
      return "CLOSED";
    }
    else if(inState == 1){
      return "SYN_SENT";
    }
    else if(inState == 2){
      return "LISTEN";
    }
    else if(inState == 3){
      return "SYN_RCVD";
    }
    else if(inState == 4){
      return "ESTABLISHED";
    }
    else if(inState == 5){
      return "FIN_WAIT_1";
    }
    else if(inState == 6){
      return "FIN_WAIT_2";
    }
    else if(inState == 7){
      return "CLOSING";
    }
    else if(inState == 8){
      return "CLOSE_WAIT";
    }
    else if(inState == 9){
      return "LAST_ACK";
    }
    else if(inState == 10){
      return "TIME_WAIT";
    }
    else
      return "Invalid state number";
  }

  private synchronized void changeToState(int newState){
    System.out.println("!!! " + stateString(state) + "->" + stateString(newState));
    state = newState;

    if(newState == CLOSE_WAIT && wantsToClose && !finSent){
      try{
        close();
      }
      catch(IOException ioe){}
    }
    else if(newState == TIME_WAIT){
      createTimerTask(30000, null);
    }
  }

  private synchronized void sendPacket(TCPPacket inPacket, boolean resend){

    if(!resend){ //new timer, and requires the current state as a key
      TCPWrapper.send(inPacket, address);

      //only do timers for syns, syn-acks, and fins
      if(inPacket.synFlag || inPacket.finFlag){
        System.out.println("Creating new TimerTask at state " + stateString(state) + " and seqNum " + seqNum);
        timerList.put(seqNum, createTimerTask(1000, inPacket));
        packetList.put(seqNum, inPacket);
      } else if (inPacket.getData() != null && inPacket.ackFlag) {
        packetList.put(seqNum, inPacket);
        if (packetList.size() == 1) {
          base = seqNum;
          timerList.put(seqNum, createTimerTask(1000, inPacket));
        }
      }
    }
    else{ //the packet is for resending, and requires the original state as the key
      try{
        if(packetList.get(inPacket.seqNum) == inPacket){

          if (inPacket.getData() != null && inPacket.ackFlag) {
            for (TCPPacket packet : packetList.values()) {
              if (packet.seqNum >= inPacket.seqNum) {
                TCPWrapper.send(packet, address);
              }
            }
            timerList.put(inPacket.seqNum, createTimerTask(1000, inPacket));
          } else {
            System.out.println("Recreating TimerTask from seqNum " + inPacket.seqNum);
            TCPWrapper.send(inPacket, address);
            timerList.put(inPacket.seqNum, createTimerTask(1000, inPacket));
          }
        }
      }
      catch(NoSuchElementException nsee){
        System.err.println("Packet to resend was not fount in the packetList.");
        nsee.printStackTrace();
      }
    }
  }

  private synchronized void incrementCounters(TCPPacket p){
    ackNum = p.seqNum + 20 + (p.data == null ? 0 : p.data.length);

    if (p.ackNum != -1) {
      seqNum = p.ackNum;
    }
  }

  private synchronized void cancelPacketTimer(){
    //must be called before changeToState is called!!!

    if(state != CLOSING){
      timerList.get(state).cancel();
      timerList.remove(state);
      packetList.remove(state);
    }
    else{
      //the only time the state changes before an ack is received... so it must
      //look back to where the fin timer started
      timerList.get(FIN_WAIT_1).cancel();
      timerList.remove(FIN_WAIT_1);
      packetList.remove(FIN_WAIT_1);
    }
  }

  private synchronized void cancelPacketTimersFromAck(Integer ackNum) {
    Enumeration<Integer> keys = timerList.keys();
    while (keys.hasMoreElements()) {
      Integer key = keys.nextElement();
      if (key < ackNum) {
        System.out.println("Cancelling timer for seqNum: " + key.toString());
        timerList.remove(key).cancel();
        packetList.remove(key);
      }
    }
  }

  /**
   * initialize buffers and set up sequence numbers
   */
  private void initBuffers(){
  }

  /**
   * Called by the application-layer code to copy data out of the
   * recvBuffer into the application's space.
   * Must block until data is available, or until terminating is true
   * @param buffer array of bytes to return to application
   * @param length desired maximum number of bytes to copy
   * @return number of bytes copied (by definition > 0)
   */
  synchronized int getData(byte[] buffer, int length){
    while (recvBuffer == null && !terminating) {
      try {
        System.out.println("Waiting for recvBuffer to have data.");
        this.wait();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    if (recvBuffer != null) {
      System.out.println("Getting data from buffer.");
      if (length >= recvBuffer.getBufferSize()) {
        recvBuffer.copyOut(buffer, recvBuffer.getBase(), recvBuffer.getBufferSize());
        recvBuffer.advance(recvBuffer.getBufferSize());

        int returnLength;
        returnLength = recvBuffer.getBufferSize();
        recvBuffer = null;
        return returnLength;
      } else {
        recvBuffer.copyOut(buffer, recvBuffer.getBase(), length);
        recvBuffer.advance(length);
        recvBuffer = null;
        return length;
      }
    }
    return 0;
  }

  /**
   * accept data written by application into sendBuffer to send.
   * Must block until ALL data is written.
   * @param buffer array of bytes to copy into app
   * @param length number of bytes to copy
   */
  synchronized void dataFromApp(byte[] buffer, int length){
    sendBuffer = new InfiniteBuffer(length);
    sendBuffer.append(buffer, 0, length);
    sendData(length);
  }

  synchronized void sendData(int dataLength) {
    while (state != ESTABLISHED) {
      try {
        this.wait();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    System.out.println("Sending data, num packets: " + Math.ceil(dataLength / dataPacketSize));
    int packetNum = 0;

    while (dataLength - dataPacketSize >= 0) {
      System.out.println("Sending Packet Num: " + packetNum);
      dataLength -= dataPacketSize;

      byte[] packetData = new byte[dataPacketSize];

      sendBuffer.copyOut(packetData, sendBuffer.getBase(), dataPacketSize);
      sendBuffer.advance(dataPacketSize);

      prevPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, packetData);
      sendPacket(prevPacket, false);
      incrementCounters(prevPacket);
      packetNum++;
    }

    if (dataLength > 0) {
      System.out.println("Sending Packet Num: " + packetNum);

      byte[] packetData = new byte[dataLength];

      sendBuffer.copyOut(packetData, sendBuffer.getBase(), dataLength);
      sendBuffer.advance(dataLength);

      prevPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, packetData);
      sendPacket(prevPacket, false);
      incrementCounters(prevPacket);
    }
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param      address   the IP address of the remote host.
   * @param      port      the port number.
   * @exception  IOException  if an I/O error occurs when attempting a
   *               connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException{
    //client state
    localport = D.getNextAvailablePort();

    this.address = address;
    this.port = port;

    D.registerConnection(address, localport, port, this);

    seqNum = 100;
    TCPPacket synPacket = new TCPPacket(localport, port, seqNum, ackNum, false, true, false, 1, null);
    changeToState(SYN_SENT);
    sendPacket(synPacket, false);
  }

  /**
   * Called by Demultiplexer when a packet comes in for this connection
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p){

    this.notifyAll();

    System.out.println("Packet received from address " + p.sourceAddr + " with seqNum " + p.seqNum + " is being processed.");
    System.out.print("The packet is ");

    if(p.ackFlag && p.synFlag){
      System.out.println("a syn-ack.");

      if(state == SYN_SENT){
        //client state
        // cancelPacketTimer();
        cancelPacketTimersFromAck(p.ackNum);
        incrementCounters(p);
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        changeToState(ESTABLISHED);
        sendPacket(ackPacket, false);
      }
      else if (state == ESTABLISHED){
        //client state, strange message due to packet loss
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        sendPacket(ackPacket, false);
      }
      else if (state == FIN_WAIT_1){
        //client state, strange message due to packet loss
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        sendPacket(ackPacket, false);
      }
    }
    else if(p.ackFlag){
      System.out.println("an ack.");
      //for the love of God, do not incrementCounters(p) in here

      if(state == SYN_RCVD){
        //server state
        // cancelPacketTimer();
        cancelPacketTimersFromAck(p.ackNum);
        changeToState(ESTABLISHED);
      }
      else if(state == FIN_WAIT_1){
        //client state
        // cancelPacketTimer();
        cancelPacketTimersFromAck(p.ackNum);
        changeToState(FIN_WAIT_2);
      }
      else if(state == LAST_ACK){
        //server state
        // cancelPacketTimer();
        cancelPacketTimersFromAck(p.ackNum);
        changeToState(TIME_WAIT);
      }
      else if(state == CLOSING){
        //client or server state
        // cancelPacketTimer();
        cancelPacketTimersFromAck(p.ackNum);
        changeToState(TIME_WAIT);
      }
      else if (p.getData() != null && p.seqNum == ackNum) {
        recvBuffer = new InfiniteBuffer(p.getData().length);
        recvBuffer.append(p.getData(), recvBuffer.getBase(), p.getData().length);
        this.notifyAll();
        incrementCounters(p);
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        sendPacket(ackPacket, false);
      }
      else if (state == ESTABLISHED && prevPacket != null) {
        if (p.ackNum >= base + packetList.get(base).getData().length + 20) {
          seqNum = p.ackNum;
          packetList.remove(base);
          timerList.remove(base);
          base += (packetList.get(base).getData().length + 20);
        } else {
          System.out.println("Received incorrect ack number. Got: " + p.ackNum
                  + " Expected: " + (20 + prevPacket.getData().length));
          // sendPacket(prevPacket, true);
        }
      }
    }
    else if(p.synFlag){
      System.out.println("a syn.");

      if(state == LISTEN){
        //server state
        try{
          D.unregisterListeningSocket(localport, this);	                     //***********tricky*************
          D.registerConnection(p.sourceAddr, p.destPort, p.sourcePort, this); //***********tricky*************
        }
        catch(IOException e){
          System.out.println("Error occured while attempting to establish connection");
        }

        this.address = p.sourceAddr;
        this.port = p.sourcePort;

        incrementCounters(p);
        TCPPacket synackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, true, false, 1, null);
        changeToState(SYN_RCVD);
        sendPacket(synackPacket, false);
      }

    }
    else if(p.finFlag){
      System.out.println("a fin.");

      if(state == ESTABLISHED){
        //server state
        incrementCounters(p);
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        changeToState(CLOSE_WAIT);
        sendPacket(ackPacket, false);
      }
      else if(state == FIN_WAIT_1){
        //client state or server state
        incrementCounters(p);
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        changeToState(CLOSING);
        sendPacket(ackPacket, false);
      }
      else if(state == FIN_WAIT_2){
        //client state
        incrementCounters(p);
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        changeToState(TIME_WAIT);
        sendPacket(ackPacket, false);
      }
      else if(state == LAST_ACK){
        //server state, strange message due to packet loss
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        sendPacket(ackPacket, false);
      }
      else if(state == CLOSING){
        //client or server state, strange message due to packet loss
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        sendPacket(ackPacket, false);
      }
      else if(state == TIME_WAIT){
        //client or server state, strange message due to packet loss
        TCPPacket ackPacket = new TCPPacket(localport, port, seqNum, ackNum, true, false, false, 1, null);
        sendPacket(ackPacket, false);
      }
    }
    else{
      System.out.println("a chunk of data.");
    }
  }

  /**
   * Waits for an incoming connection to arrive to connect this socket to
   * Ultimately this is called by the application calling
   * ServerSocket.accept(), but this method belongs to the Socket object
   * that will be returned, not the listening ServerSocket.
   * Note that localport is already set prior to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
    //server state
    changeToState(LISTEN);

    D.registerListeningSocket (localport, this);

    seqNum = 10000;

    try{
      this.wait();
    }
    catch(InterruptedException e){
      System.err.println("Error occured when trying to wait.");
    }
  }


  /**
   * Returns an input stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     a stream for reading from this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               input stream.
   */
  public InputStream getInputStream() throws IOException {
    return appIS;
  }

  /**
   * Returns an output stream for this socket.  Note that this method cannot
   * create a NEW InputStream, but must return a reference to an
   * existing InputStream (that you create elsewhere) because it may be
   * called more than once.
   *
   * @return     an output stream for writing to this socket.
   * @exception  IOException  if an I/O error occurs when creating the
   *               output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    return appOS;
  }


  /**
   * Closes this socket.
   *
   * @exception  IOException  if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {
    if (address==null) {
      return;
    }

    System.out.println("*** close() was called by the application.");
    terminating = true;

    if(state == ESTABLISHED){
      //client state
      TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
      changeToState(FIN_WAIT_1);
      sendPacket(finPacket, false);
      finSent = true;
    }
    else if(state == CLOSE_WAIT){
      //server state
      TCPPacket finPacket = new TCPPacket(localport, port, seqNum, ackNum, false, false, true, 1, null);
      changeToState(LAST_ACK);
      sendPacket(finPacket, false);
      finSent = true;
    }
    else{
      System.out.println("Attempted to close while not established (ESTABLISHED) or waiting to close (CLOSE_WAIT)");
      //timer task here... try the closing process again
      wantsToClose = true;
    }

    while(!reader.tryClose()){
      notifyAll();
      try{
        wait(1000);
      }
      catch(InterruptedException e){}
    }
    writer.close();

    notifyAll();

    //returns immediately to the application
  }

  /**
   * create TCPTimerTask instance, handling tcpTimer creation
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref){
    if(tcpTimer == null)
      tcpTimer = new Timer(false);
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }


  /**
   * handle timer expiration (called by TCPTimerTask)
   * @param ref Generic reference that can be used by the timer to return
   * information.
   */
  public synchronized void handleTimer(Object ref){

    if(ref == null){
      // this must run only once the last timer (30 second timer) has expired
      tcpTimer.cancel();
      tcpTimer = null;

      try{
        D.unregisterConnection(address, localport, port, this);
      }
      catch(IOException e){
        System.out.println("Error occured while attempting to close connection");
      }
    }
    else{	//its a packet that needs to be resent
      System.out.println("XXX Resending Packet");
      System.out.println("Packet Resending: Seq: " + ((TCPPacket) ref).seqNum +
              " Ack: " + ((TCPPacket) ref).ackNum + " AckBool: " +
              ((TCPPacket) ref).ackFlag + " SynBool: " +
              ((TCPPacket) ref).synFlag);
      sendPacket((TCPPacket)ref, true);
    }
  }
}
