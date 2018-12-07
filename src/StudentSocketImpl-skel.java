import java.net.*;
import java.io.*;
import java.util.HashMap;
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
  private State prevState;

  private Demultiplexer D;
  private Timer tcpTimer;
  private int current_sequence;
  private int current_ack;
  private HashMap<State, TCPPacket> packetsSent;

  StudentSocketImpl(Demultiplexer D) { // default constructor
    currentState = State.CLOSED;
    this.D = D;
    packetsSent = new HashMap<>();
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
      sendPacket(address, localport, port, current_sequence, current_ack, false, true, false);
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

  private void sendPacket(InetAddress address, int localPort, int port,
                                 int current_sequence, int current_ack,
                                 boolean ackFlag, boolean synFlag,
                                 boolean finFlag) {
    TCPPacket packToSend = new TCPPacket(localPort,
            port, current_sequence, current_ack, ackFlag, synFlag,
            finFlag, 0, null);
    TCPWrapper.send(packToSend, address);
    if (!(ackFlag && !synFlag)) {
      createTimerTask(2500, null);
    }
    packetsSent.putIfAbsent(currentState, packToSend);
  }

  /** Resends a packet that was already sent when at the given state.
   * @param address The address to send the packet to.
   * @param state The state where the packet was sent in.
   */
  private void resendPacketFromState(InetAddress address, State state) {
    TCPWrapper.send(packetsSent.get(state), address);
    System.out.println("Resending packet from state: " + state.toString());
    if (!(packetsSent.get(state).ackFlag && !packetsSent.get(state).synFlag)) {
      createTimerTask(2500, null);
    }
  }

  /**
   * Called by Demultiplexer when a packet comes in for this connection.
   *
   * @param p The packet that arrived
   */
  public synchronized void receivePacket(TCPPacket p) {
    logPacket(p);
    // Cases for each state and the expected packet and response. This includes
    // error correction where packets will be resent.
    switch (currentState) {
      case SYN_SENT:
        if (p.synFlag && p.ackFlag) {
          stopTimer();

          current_ack = p.ackNum;
          sendPacket(address, localport, port, current_sequence, current_ack, true, false, false);
          transitonState(State.ESTABLISHED);
        }
        break;

      case ESTABLISHED:
        if (p.finFlag) {
          updateSeqAckAndAddress(p);
          sendPacket(address, localport, port, current_sequence, current_ack, true, false, false);
          transitonState(State.CLOSE_WAIT);
        } else if (p.synFlag && p.ackFlag) {
          resendPacketFromState(address, State.SYN_SENT);
        }
        break;

      case LISTEN:
        if (p.synFlag && !p.ackFlag) {

          port = p.sourcePort;
          address = p.sourceAddr;
          sendPacket(address, localport, port, current_sequence, current_ack, true, true, false);
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
          stopTimer();
          transitonState(State.ESTABLISHED);
        } else if (p.synFlag) {
          resendPacketFromState(address, State.LISTEN);
        }
        break;

      case FIN_WAIT_1:
        if (p.finFlag) {
          updateSeqAckAndAddress(p);
          sendPacket(address, localport, port, current_sequence, current_ack, true, false, false);
          transitonState(State.CLOSING);
        } else if (p.ackFlag) {
          if (p.synFlag) {
            resendPacketFromState(address, State.SYN_SENT);
          } else {
            stopTimer();
            transitonState(State.FIN_WAIT_2);
          }
        }
        break;

      case CLOSING:
        if (p.ackFlag) {
          stopTimer();
          transitonState(State.TIME_WAIT);
          createTimerTask(30000, null);
        }
        else if (p.finFlag) {
          resendPacketFromState(address, State.FIN_WAIT_1);
        }
        break;

      case FIN_WAIT_2:
        if (p.finFlag) {
          updateSeqAckAndAddress(p);
          sendPacket(address, localport, port, current_sequence, current_ack, true, false, false);
          transitonState(State.TIME_WAIT);
          createTimerTask(30000, null);
        }
        break;

      case CLOSE_WAIT:
        if (p.finFlag) {
          resendPacketFromState(address, State.ESTABLISHED);
        }
        break;

      case LAST_ACK:
        if (p.ackFlag) {
          stopTimer();
          transitonState(State.TIME_WAIT);
          createTimerTask(30000, null);
        }
        if (p.finFlag) {
          resendPacketFromState(address, State.CLOSE_WAIT);
        }

      case TIME_WAIT:
        if (p.finFlag) {
          resendPacketFromState(address, State.FIN_WAIT_2);
        }
        if (p.ackFlag) {
          if (prevState == State.CLOSING) {
            resendPacketFromState(address, State.FIN_WAIT_1);
          }
        }
    }
    this.notifyAll();
  }

  private void stopTimer() {
    tcpTimer.cancel();
    tcpTimer = null;
  }

  /**
   * Updates the sequence and ack number from the incoming packet.
   * @param p Newly received packet.
   */
  private void updateSeqAckAndAddress(TCPPacket p) {
    current_sequence = p.ackNum;
    current_ack = p.seqNum + 1;
  }

  private void logPacket(TCPPacket packet) {
    String message = String.format("\n===Packet Received===\n" +
            "Source Address: %s\nSource Port: %s\nDestination Port: %s\n",
            packet.sourceAddr.getHostAddress(), packet.sourcePort,
            packet.destPort);
    System.out.println(message);
  }

  private void transitonState(State newState) {
    System.out.println(String.format("!!! %s->%s", currentState.toString(),
            newState.toString()));
    prevState = currentState;
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
      D.registerListeningSocket(this.localport, this);
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
    if (currentState != State.CLOSED) {
      sendPacket(address, localport, port, current_sequence, current_ack, false, false, true);

      if (currentState == State.ESTABLISHED) {
        transitonState(State.FIN_WAIT_1);
      } else if (currentState == State.CLOSE_WAIT) {
        transitonState(State.LAST_ACK);
      }
      new Thread(new FinishRunnable(this)).start();
    }
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
    stopTimer();

    if (currentState == State.TIME_WAIT) {
      transitonState(State.CLOSED);
      notifyAll();

      try {
        D.unregisterConnection(address, localport, port, this);
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      resendPacketFromState(address, prevState);
    }
  }

  /**
   * Runnable to manage closing the socket and releasing the caller of the close
   * method. This runnable is passed to a thread anonymously.
   */
  private class FinishRunnable implements Runnable {
    private final StudentSocketImpl currentSock;

    FinishRunnable(StudentSocketImpl currentSock) {
      this.currentSock = currentSock;
    }

    @Override
    public void run() {
      while (currentState != State.CLOSED) {
        try {
          synchronized (currentSock) {
            currentSock.wait();
          }
        } catch (InterruptedException e) {
          e.printStackTrace();
        }
      }
    }
  }
}
