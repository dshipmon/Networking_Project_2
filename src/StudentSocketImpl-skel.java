import java.net.*;
import java.io.*;
import java.util.Timer;

/**
 * A class implementing a basic TCP socket.
 *
 * @author Dominique Shipmon
 */
class StudentSocketImpl extends BaseSocketImpl {

  // SocketImpl data members:
  //   protected InetAddress address;
  //   protected int port;
  //   protected int localport;

  private enum State {
    CLOSED, LISTEN, SYN_SENT, SYN_RCVD, ESTABLISHED, CLOSE_WAIT, FIN_WAIT_1,
    CLOSING, FIN_WAIT_2, TIME_WAIT, LAST_ACK
  }

  private State currentState;

  private Demultiplexer D;
  private Timer tcpTimer;
  private int current_sequence;
  private int current_ack;

  StudentSocketImpl(Demultiplexer D) { // default constructor
    currentState = State.CLOSED;
    this.D = D;
  }

  /**
   * Connects this socket to the specified port number on the specified host.
   *
   * @param address the IP address of the remote host.
   * @param port the port number.
   * @exception IOException if an I/O error occurs when attempting a connection.
   */
  public synchronized void connect(InetAddress address, int port) throws IOException {
    if (currentState == State.CLOSED) {
      localport = D.getNextAvailablePort();
      this.port = port;
      this.address = address;
      D.registerConnection(address, localport, port, this);
      TCPWrapper.send(new TCPPacket(localport,
              port, current_sequence, current_ack, false, true,
              false, 0, null), address);
      transitonState(State.SYN_SENT);
      current_sequence++;

      while (currentState != State.ESTABLISHED) {
        try {
          wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }

  }

  /**
   * Called by Demultiplexer when a packet comes in for this connection
   *
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p) {
    logPacket(p);
    switch (currentState) {
      case SYN_SENT:
        if (p.synFlag && p.ackFlag) {
          current_ack = p.ackNum;
          TCPWrapper.send(new TCPPacket(localport, port, current_sequence,
                  current_ack, true, false, false,
                  0, null), address);
          transitonState(State.ESTABLISHED);
        }
        break;
      case ESTABLISHED:
        if (!p.finFlag) {
          current_sequence = p.ackNum;
          current_ack += p.data.length;
        }
        break;
      case LISTEN:
        if (p.synFlag && !p.ackFlag) {
          current_sequence = p.ackNum;
          current_ack = p.seqNum + 1;
          port = p.sourcePort;
          address = p.sourceAddr;
          TCPWrapper.send(new TCPPacket(localport, port, current_sequence,
                  current_ack, true, true, false,
                  0, null), address);
          transitonState(State.SYN_RCVD);
          try{
            D.unregisterListeningSocket(localport, this);
            D.registerConnection(address, localport, port, this);
          } catch (IOException e) {
            e.printStackTrace();
            return;
          }
        }
        break;
      case SYN_RCVD:
        if (p.ackFlag) {
          transitonState(State.ESTABLISHED);
        }
        break;
    }
    this.notifyAll();
  }

  private void logPacket(TCPPacket packet) {
    String message = String.format("\n===Packet Received===\n" +
            "Source Address: %s\nSource Port: %s\nDestination Port: %s\n",
            packet.sourceAddr.getHostAddress(), packet.sourcePort,
            packet.destPort);
    System.out.println(message);
  }

  private void transitonState(State newState) {
    System.out.println(String.format("!!!%s->%s", currentState.toString(),
            newState.toString()));
    currentState = newState;
  }

  /**
   * Waits for an incoming connection to arrive to connect this socket to Ultimately this is called
   * by the application calling ServerSocket.accept(), but this method belongs to the Socket object
   * that will be returned, not the listening ServerSocket. Note that localport is already set prior
   * to this being called.
   */
  public synchronized void acceptConnection() throws IOException {
    if (currentState == State.CLOSED) {
      localport = D.getNextAvailablePort();
      D.registerListeningSocket(localport, this);
      transitonState(State.LISTEN);
      while (currentState != State.ESTABLISHED) {
        try {
          wait();
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }

  /**
   * Returns an input stream for this socket. Note that this method cannot create a NEW InputStream,
   * but must return a reference to an existing InputStream (that you create elsewhere) because it
   * may be called more than once.
   *
   * @return a stream for reading from this socket.
   * @exception IOException if an I/O error occurs when creating the input stream.
   */
  public InputStream getInputStream() throws IOException {
    // project 4 return appIS;
    return null;
  }

  /**
   * Returns an output stream for this socket. Note that this method cannot create a NEW
   * InputStream, but must return a reference to an existing InputStream (that you create elsewhere)
   * because it may be called more than once.
   *
   * @return an output stream for writing to this socket.
   * @exception IOException if an I/O error occurs when creating the output stream.
   */
  public OutputStream getOutputStream() throws IOException {
    // project 4 return appOS;
    return null;
  }

  /**
   * Closes this socket.
   *
   * @exception IOException if an I/O error occurs when closing this socket.
   */
  public synchronized void close() throws IOException {

  }

  /**
   * create TCPTimerTask instance, handling tcpTimer creation
   *
   * @param delay time in milliseconds before call
   * @param ref generic reference to be returned to handleTimer
   */
  private TCPTimerTask createTimerTask(long delay, Object ref) {
    if (tcpTimer == null) {
      tcpTimer = new Timer(false);
    }
    return new TCPTimerTask(tcpTimer, delay, this, ref);
  }

  /**
   * handle timer expiration (called by TCPTimerTask)
   *
   * @param ref Generic reference that can be used by the timer to return information.
   */
  public synchronized void handleTimer(Object ref) {

    // this must run only once the last timer (30 second timer) has expired
    tcpTimer.cancel();
    tcpTimer = null;
  }
}
