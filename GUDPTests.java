import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class GUDPTests {
    public static void main(String args[]) throws IOException {
        InetAddress address = InetAddress.getLocalHost();
        InetAddress address2 = InetAddress.getByName("192.168.1.130");
        System.out.println(address);
        System.out.println(address2);
        GUDPSocket gudpSocket = new GUDPSocket(new DatagramSocket());

        byte[] buffer = new byte[10];
        DatagramPacket packet = new DatagramPacket(buffer, 0, 10, address, gudpSocket.getPort());

        byte[] buffer2 = new byte[1500];
        DatagramPacket packet2 = new DatagramPacket(buffer, buffer.length);

        gudpSocket.send(packet);
        gudpSocket.send(packet);
        gudpSocket.send(packet);
        gudpSocket.send(packet);
        // gudpSocket.send(packet2);

        // try {
        // Thread.sleep(7000);
        // } catch (Exception e) {
        // // TODO: handle exception
        // }

        gudpSocket.receive(packet2);

        // gudpSocket.close();

    }
}
