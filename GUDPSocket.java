import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.Random;
import java.io.IOException;

public class GUDPSocket implements GUDPSocketAPI {
    DatagramSocket datagramSocket;

    private LinkedList<GUDPEndPoint> sendQueue = new LinkedList<>();
    private LinkedList<GUDPEndPoint> receiveQueue = new LinkedList<>();

    private SenderThread senderThread = new SenderThread();
    private boolean senderThreadRunning;

    private ReceiverThread receiverThread = new ReceiverThread();
    private boolean receiverThreadRunning;

    private Object finishWaiting;

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
        senderThreadRunning = true;
        receiverThreadRunning = true;
    }

    public int getPort() {
        return this.datagramSocket.getLocalPort();
    }

    public InetAddress getAddress() {
        return this.datagramSocket.getLocalAddress();
    }

    public void send(DatagramPacket packet) throws IOException {
        GUDPEndPoint packetEndPoint = getPacketEndPoint(packet);
        int endPointQueueIndex = getEnpointSendQueueIndex(packetEndPoint);

        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);

        if (!this.senderThread.isAlive()) {
            senderThread.start();
        }

        if (!this.receiverThread.isAlive()) {
            receiverThread.start();
        }

        synchronized (this.sendQueue) {
            GUDPEndPoint endPoint = this.sendQueue.get(endPointQueueIndex);
            int last = endPoint.getLast();
            gudppacket.setSeqno(last + 1);
            endPoint.setLast(last + 1);
            if (endPoint.getState() == GUDPEndPoint.endPointState.FINISHED
                    || endPoint.getState() == GUDPEndPoint.endPointState.CLOSED)
                endPoint.setState(GUDPEndPoint.endPointState.READY);

            this.sendQueue.get(endPointQueueIndex).add(gudppacket);
            this.sendQueue.notifyAll();

            // System.out.println("Added packet seq nbr " + (last + 1) + " to endpoint " +
            // endPoint);
        }

        // datagramSocket.send(udppacket);
    }

    public void receive(DatagramPacket packet) throws IOException {
        // byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        // DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        // datagramSocket.receive(udppacket);
        // GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        // gudppacket.decapsulate(packet);

        InetAddress packetAddress = packet.getAddress();
        int packetPort = packet.getPort();

        if (!this.receiverThread.isAlive())
            receiverThread.start();

        while (this.receiveQueue.size() == 0 || !messagesInSocketQueue(this.receiveQueue)) {
            try {
                this.receiveQueue.wait();
            } catch (Exception e) {
                // TODO: handle exception
            }

        }

        if (packetAddress == null) {
            for (GUDPEndPoint endPoint : this.receiveQueue) {
                if (endPoint.isEmptyBuffer())
                    continue;

                GUDPPacket gudpPacketReceived = endPoint.remove();
                gudpPacketReceived.decapsulate(packet);
                System.out.println("RECEIVE METHOD " + packet.getAddress() + ':' + packet.getPort());
                return;
            }
        }

        for (GUDPEndPoint endPoint : this.receiveQueue) {
            if (endPoint.isEmptyBuffer())
                continue;
            if (!(packetAddress.equals(endPoint.getRemoteEndPoint().getAddress())
                    && packetPort == endPoint.getRemoteEndPoint().getPort()))
                continue;

            GUDPPacket gudpPacketReceived = endPoint.remove();
            gudpPacketReceived.decapsulate(packet);
            System.out.println("RECEIVE METHOD " + packet.getAddress() + ':' + packet.getPort());
            return;
        }
    }

    public void finish() throws IOException {
        for (GUDPEndPoint endPoint : this.sendQueue) {
            int lastSeq = endPoint.getLast();
            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();

            byte[] buffer = new byte[0];
            DatagramPacket FINPacket = new DatagramPacket(buffer, 0, endPointSocketAddress);

            GUDPPacket gudpPacket = GUDPPacket.encapsulate(FINPacket);
            gudpPacket.setType(GUDPPacket.TYPE_FIN);
            gudpPacket.setSeqno(lastSeq + 1);
            endPoint.setLast(lastSeq + 1);

            endPoint.add(gudpPacket);
            endPoint.setState(GUDPEndPoint.endPointState.FINISHED);
            System.out.println("Finish: adding FINPACKET to endpoint " + endPointSocketAddress);
            synchronized (this.sendQueue) {
                this.sendQueue.notifyAll();
            }
        }

        while (!allEndpointsClosed(sendQueue)) {
            try {
                finishWaiting.wait();
            } catch (Exception e) {
                // TODO: handle exception
            }
        }

        System.out.println("FINISH RETURN");
        return;
    }

    public void close() throws IOException {
        System.out.println("------------");
        System.out.println("APP: Closing socket " + this.datagramSocket.getLocalSocketAddress());
        this.senderThreadRunning = false;
        this.receiverThreadRunning = false;
        this.senderThread.interrupt();
        this.receiverThread.interrupt();
        clearSendQueue();
        if (!this.datagramSocket.isClosed())
            this.datagramSocket.close();
    }

    private void clearSendQueue() {
        for (GUDPEndPoint endPoint : this.sendQueue) {
            endPoint.removeAll();
            endPoint.stopTimer();
            endPoint.setState(GUDPEndPoint.endPointState.CLOSED);
        }

        this.sendQueue.clear();
    }

    private int getEnpointSendQueueIndex(GUDPEndPoint endPoint) throws IOException {
        synchronized (this.sendQueue) {

            for (GUDPEndPoint queuedEndPoint : this.sendQueue) {
                if (endPoint.getRemoteEndPoint().getAddress().equals(queuedEndPoint.getRemoteEndPoint().getAddress())
                        && endPoint.getRemoteEndPoint().getPort() == queuedEndPoint.getRemoteEndPoint().getPort()) {
                    return this.sendQueue.indexOf(queuedEndPoint);
                }
            }
            Random rand = new Random();
            int endPointBSN = rand.nextInt();

            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();
            System.out.println("Creating new endpoint " + endPointSocketAddress);

            endPoint.setBase(endPointBSN);
            endPoint.setNextseqnum(endPointBSN);

            byte[] buffer = new byte[0];
            DatagramPacket BSNPacket = new DatagramPacket(buffer, 0, endPointSocketAddress);

            GUDPPacket gudpPacket = GUDPPacket.encapsulate(BSNPacket);
            gudpPacket.setType(GUDPPacket.TYPE_BSN);
            gudpPacket.setSeqno(endPointBSN);

            endPoint.add(gudpPacket);
            endPoint.setLast(endPointBSN);

            this.sendQueue.add(endPoint);
            return this.sendQueue.indexOf(endPoint);
        }
    }

    private GUDPEndPoint getPacketEndPoint(DatagramPacket packet) {
        InetAddress packetAddress = packet.getAddress();
        int packetPort = packet.getPort();
        return new GUDPEndPoint(packetAddress, packetPort);
    }

    private GUDPEndPoint getPacketEndPoint(GUDPPacket packet) {
        InetAddress packetAddress = packet.getSocketAddress().getAddress();
        int packetPort = packet.getSocketAddress().getPort();
        return new GUDPEndPoint(packetAddress, packetPort);
    }

    private boolean messagesInSocketQueue(LinkedList<GUDPEndPoint> queue) {
        for (GUDPEndPoint gudpEndPoint : queue) {
            if (!gudpEndPoint.isEmptyBuffer())
                return true;
        }
        return false;
    }

    private boolean allEndpointsClosed(LinkedList<GUDPEndPoint> queue) {
        for (GUDPEndPoint endPoint : queue) {
            if (endPoint.getState() != GUDPEndPoint.endPointState.CLOSED)
                return false;
        }
        return true;
    }

    public String toString() {
        return "" + getAddress() + ":" + getPort();
    }

    private class SenderThread extends Thread {

        public SenderThread() {
        }

        public void run() {
            while (GUDPSocket.this.senderThreadRunning) {

                synchronized (GUDPSocket.this.sendQueue) {
                    while (GUDPSocket.this.senderThreadRunning && (GUDPSocket.this.sendQueue.size() == 0
                            || !messagesInSocketQueue(GUDPSocket.this.sendQueue))) {
                        try {
                            System.out.println(">>Sender Thread waiting..." + GUDPSocket.this.senderThreadRunning);
                            GUDPSocket.this.sendQueue.wait();
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                            System.err.println(">>Sender Thread interrupted " + e);
                        }
                    }

                    for (GUDPEndPoint endPoint : GUDPSocket.this.sendQueue) {
                        try {
                            handleGUDPEndpoint(endPoint);
                        } catch (Exception e) {
                            System.err.println(">>Sender Thread: Error sending packet on enpoint"
                                    + endPoint.getRemoteEndPoint() + ". error: " + e);
                        }
                    }
                }
            }
        }

        private void handleGUDPEndpoint(GUDPEndPoint endPoint) throws IOException {
            GUDPEndPoint.endPointState state = endPoint.getState();
            GUDPEndPoint.readyEvent event = endPoint.getEvent();

            if (event == GUDPEndPoint.readyEvent.WAIT || event == GUDPEndPoint.readyEvent.RECEIVE
                    || state == GUDPEndPoint.endPointState.CLOSED)
                return;

            System.out.println();
            String debugOutput = "\n";
            debugOutput += ">>Sender Thread handling endpoint " + endPoint.getRemoteEndPoint() + "\n  Socket: "
                    + GUDPSocket.this;
            debugOutput += "\n--Data of packets sent--\n";

            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();

            if (state == GUDPEndPoint.endPointState.MAXRETRIED) {
                endPoint.removeAll();
                endPoint.setState(GUDPEndPoint.endPointState.CLOSED);
                throw new IOException("Endpoint " + endPointSocketAddress + " reached max retry.");
            }

            if (state == GUDPEndPoint.endPointState.FINISHED) {
                if (endPoint.getBase() == endPoint.getLast()) {
                    endPoint.setState(GUDPEndPoint.endPointState.CLOSED);
                    endPoint.removeAll();
                    endPoint.stopTimer();
                    GUDPSocket.this.finishWaiting.notifyAll();
                }
            }

            if (event == GUDPEndPoint.readyEvent.INIT) {
                int endPointBSN = endPoint.getBase();

                GUDPPacket gudpPacket = endPoint.getPacket(endPointBSN);
                if (gudpPacket != null) {
                    DatagramPacket udpPacket = gudpPacket.pack();

                    GUDPSocket.this.datagramSocket.send(udpPacket);
                    debugOutput += GUDPSocket.bytesToHex(gudpPacket.getBytes()) + "\n";

                    // GUDPPacket firstDataPacket = endPoint.remove();
                    // endPoint.add(gudpPacket);
                    // endPoint.add(firstDataPacket);

                    endPoint.setNextseqnum(endPointBSN + 1);

                    endPoint.setState(GUDPEndPoint.endPointState.BSN);
                    endPoint.setEvent(GUDPEndPoint.readyEvent.SEND);

                    // FOR TESTING ONLY
                    // endPoint.removeAll();
                } else {
                    debugOutput += "Could not find BSN packet\n";
                }

            } else if (event == GUDPEndPoint.readyEvent.SEND) {
                if (state == GUDPEndPoint.endPointState.BSN || state == GUDPEndPoint.endPointState.READY
                        || state == GUDPEndPoint.endPointState.FINISHED) {
                    int windowSize = endPoint.getWindowSize();
                    int base = endPoint.getBase();

                    for (int i = base; i < base + windowSize; i++) {
                        int nextSeq = endPoint.getNextseqnum();
                        if (i < nextSeq)
                            continue;

                        GUDPPacket packet = endPoint.getPacket(i);
                        if (packet == null || packet.getSeqno() != nextSeq)
                            continue;

                        GUDPSocket.this.datagramSocket.send(packet.pack());
                        debugOutput += GUDPSocket.bytesToHex(packet.getBytes()) + "\n";

                        endPoint.setNextseqnum(nextSeq + 1);
                    }

                    endPoint.startTimer();
                    endPoint.setEvent(GUDPEndPoint.readyEvent.WAIT);
                    // endPoint.removeAll();

                }
            } else if (event == GUDPEndPoint.readyEvent.TIMEOUT) {
                int windowSize = endPoint.getWindowSize();
                int base = endPoint.getBase();
                int retry = endPoint.getRetry();
                int maxRetry = endPoint.getMaxRetry();

                if (retry <= maxRetry) {
                    for (int i = base; i < base + windowSize; i++) {
                        GUDPPacket packet = endPoint.getPacket(i);
                        if (packet == null)
                            continue;

                        GUDPSocket.this.datagramSocket.send(packet.pack());
                        debugOutput += GUDPSocket.bytesToHex(packet.getBytes()) + "\n";

                    }

                    endPoint.setRetry(retry + 1);
                    endPoint.startTimer();
                    endPoint.setEvent(GUDPEndPoint.readyEvent.WAIT);
                } else {
                    endPoint.setState(GUDPEndPoint.endPointState.MAXRETRIED);
                }

            }
            debugOutput += "--Data of packets sent--\n";
            debugOutput += "  Endpoint " + endPoint.getRemoteEndPoint() + " processed \n   state: " + state
                    + "\n   Base: " + endPoint.getBase()
                    + "\n   NextSeq: "
                    + endPoint.getNextseqnum()
                    + "\n   LastSeq: " + endPoint.getLast()
                    + "\n   Retry: " + endPoint.getRetry()
                    + "\n";

            System.out.println(debugOutput);
        }
    }

    private class ReceiverThread extends Thread {
        public ReceiverThread() {
        }

        public void run() {
            while (GUDPSocket.this.receiverThreadRunning) {
                synchronized (GUDPSocket.this.receiveQueue) {

                    GUDPPacket receivedPacket;
                    try {
                        System.out.println(">>Receiver Thread: " + GUDPSocket.this
                                + " : waiting for packet...");
                        receivedPacket = receivePacket();
                        handleGUDPPacket(receivedPacket);
                    } catch (Exception e) {
                        System.err.println(">>Receiver Thread: error receiving packet on socket "
                                + GUDPSocket.this);
                    }

                }
            }
        }

        private GUDPPacket receivePacket() throws IOException {

            byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
            DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
            GUDPSocket.this.datagramSocket.receive(udppacket);
            GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);

            return gudppacket;
        }

        private void handleGUDPPacket(GUDPPacket packet) throws IOException {
            short type = packet.getType();
            int sequenceNumber = packet.getSeqno();
            GUDPEndPoint packetEndPoint = GUDPSocket.this.getPacketEndPoint(packet);
            int endPointIndex = getEndpointReceiveQueueIndex(packetEndPoint);

            System.out.println(">>Receiver thread received packet on " + GUDPSocket.this + "\n  packet type: " + type
                    + "\n  seqno: " + sequenceNumber
                    + "\n  content " + bytesToHex(packet.getBytes()));
            if (type == GUDPPacket.TYPE_BSN) {
                packetEndPoint = GUDPSocket.this.receiveQueue.get(endPointIndex);

                packetEndPoint.setExpectedseqnum(sequenceNumber);
                sendAck(packetEndPoint);
            } else if (type == GUDPPacket.TYPE_DATA) {
                packetEndPoint = GUDPSocket.this.receiveQueue.get(endPointIndex);

                packetEndPoint.setExpectedseqnum(sequenceNumber);
                sendAck(packetEndPoint);

                packetEndPoint.add(packet);
                GUDPSocket.this.receiveQueue.notifyAll();
            } else if (type == GUDPPacket.TYPE_ACK) {
                endPointIndex = GUDPSocket.this.getEnpointSendQueueIndex(packetEndPoint);
                packetEndPoint = GUDPSocket.this.sendQueue.get(endPointIndex);

                System.out.println("UPDATING ENDPOINT " + packetEndPoint.getRemoteEndPoint());

                updateEndpointOnACK(packetEndPoint, sequenceNumber);
            } else if (type == GUDPPacket.TYPE_FIN) {
                packetEndPoint = GUDPSocket.this.receiveQueue.get(endPointIndex);

                packetEndPoint.setExpectedseqnum(sequenceNumber);
                sendAck(packetEndPoint);
            }
        }

        private int getEndpointReceiveQueueIndex(GUDPEndPoint endPoint) {
            synchronized (GUDPSocket.this.receiveQueue) {
                for (GUDPEndPoint queuedEndPoint : GUDPSocket.this.receiveQueue) {
                    if (endPoint.getRemoteEndPoint().getAddress() == queuedEndPoint.getRemoteEndPoint().getAddress()
                            && endPoint.getRemoteEndPoint().getPort() == queuedEndPoint.getRemoteEndPoint().getPort()) {
                        return GUDPSocket.this.receiveQueue.indexOf(queuedEndPoint);
                    }
                }
                endPoint.setState(GUDPEndPoint.endPointState.READY);
                endPoint.setEvent(GUDPEndPoint.readyEvent.RECEIVE);
                GUDPSocket.this.receiveQueue.add(endPoint);
                return GUDPSocket.this.receiveQueue.indexOf(endPoint);
            }
        }

        private void sendAck(GUDPEndPoint endPoint) throws IOException {
            int expectedSequenceNum = endPoint.getExpectedseqnum();
            InetSocketAddress endPointSocketAddress = endPoint.getRemoteEndPoint();
            byte[] buffer = new byte[0];

            DatagramPacket ACKPacket = new DatagramPacket(buffer, 0, endPointSocketAddress);

            GUDPPacket gudpPacket = GUDPPacket.encapsulate(ACKPacket);

            gudpPacket.setType(GUDPPacket.TYPE_ACK);
            gudpPacket.setSeqno(expectedSequenceNum + 1);

            endPoint.setExpectedseqnum(expectedSequenceNum + 1);

            DatagramPacket udpPacket = gudpPacket.pack();

            GUDPSocket.this.datagramSocket.send(udpPacket);
            System.out.println(">>Receiver thread sent ACK: \n  Socket: " + GUDPSocket.this + "\n  endpoint: "
                    + endPoint.getRemoteEndPoint() + "\n  content: "
                    + GUDPSocket.bytesToHex(gudpPacket.getBytes()) + "\n  seqno: " + gudpPacket.getSeqno());
        }

        private void updateEndpointOnACK(GUDPEndPoint endPoint, int ACK) {
            if (endPoint.getPacket(ACK - 1).getType() == GUDPPacket.TYPE_BSN)
                endPoint.setState(GUDPEndPoint.endPointState.READY);

            endPoint.removeAllACK(ACK - 1);
            endPoint.setBase(ACK);
            endPoint.stopTimer();
            endPoint.setEvent(GUDPEndPoint.readyEvent.SEND);
        }
    }

    // TESTING: print packets data

    private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

    public static String bytesToHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars, StandardCharsets.UTF_8);
    }

}
