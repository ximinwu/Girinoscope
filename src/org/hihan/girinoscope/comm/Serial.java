package org.hihan.girinoscope.comm;

import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class Serial implements Closeable {

    private static final Logger logger = Logger.getLogger(Serial.class.getName());

    /*
     * http://blog.cedarsoft.com/2010/11/setting-java-library-path-programmatically
     */
    static {
        try {
            System.setProperty("java.library.path", "lib");
            Field fieldSysPath = ClassLoader.class.getDeclaredField("sys_paths");
            fieldSysPath.setAccessible(true);
            fieldSysPath.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Fail to force the reload of system paths property.", e);
        }
    }

    /** The port we're normally going to use. */
    private static final Pattern[] ACCEPTABLE_PORT_NAMES = {
                    //
                    Pattern.compile("/dev/tty\\.usbserial-.+"), // Mac OS X
                    Pattern.compile("/dev/ttyACM\\d+"), // Raspberry Pi
                    Pattern.compile("/dev/ttyUSB\\d+"), // Linux
                    Pattern.compile("COM\\d+"), // Windows
    };

    /** Milliseconds to block while waiting for port open */
    private static final int TIME_OUT = 2000;

    /** Default bits per second for COM port. */
    private static final int DATA_RATE = 115200;

    /** Milliseconds to wait when no input is available. */
    private static final int READ_DELAY = 200;

    private SerialPort serialPort;

    /**
     * The output stream to the port.
     */
    private InputStream input;

    /**
     * A BufferedReader which will be fed by a InputStreamReader converting the
     * bytes into characters making the displayed results codepage independent.
     */
    private OutputStream output;

    public Serial(CommPortIdentifier portId) throws Exception {
        connect(portId);
    }

    public void connect(CommPortIdentifier portId) throws Exception {
        serialPort = (SerialPort) portId.open(getClass().getName(), TIME_OUT);

        serialPort.setSerialPortParams(DATA_RATE, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
        serialPort.setFlowControlMode(SerialPort.FLOWCONTROL_NONE);

        output = serialPort.getOutputStream();
        input = serialPort.getInputStream();

        serialPort.notifyOnDataAvailable(false);
    }

    public static List<CommPortIdentifier> enumeratePorts() {
        List<CommPortIdentifier> ports = new LinkedList<CommPortIdentifier>();

        Enumeration<?> portEnum = CommPortIdentifier.getPortIdentifiers();
        while (portEnum.hasMoreElements()) {
            CommPortIdentifier portIdentifier = (CommPortIdentifier) portEnum.nextElement();
            for (Pattern acceptablePortName : ACCEPTABLE_PORT_NAMES) {
                String portName = portIdentifier.getName();
                if (acceptablePortName.matcher(portName).matches()) {
                    if (portName.startsWith("/dev/ttyACM")) {
                        /*
                         * the next line is for Raspberry Pi and gets us into
                         * the while loop and was suggested here:
                         * http://www.raspberrypi
                         * .org/phpBB3/viewtopic.php?f=81&t=32186
                         */
                        System.setProperty("gnu.io.rxtx.SerialPorts", portName);
                    }
                    ports.add(portIdentifier);
                }
            }
        }

        return ports;
    }

    public String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int length = 0;
        try {
            while (true) {
                int c;
                if (input.available() > 0 && (c = input.read()) >= 0) {
                    line.append((char) c);
                    ++length;
                    if (length >= 2 && line.charAt(length - 2) == '\r' && line.charAt(length - 1) == '\n') {
                        line.setLength(length - 2);
                        break;
                    }
                } else {
                    /*
                     * Sleeping here allows us to be interruped (the serial is
                     * not by itself).
                     */
                    Thread.sleep(READ_DELAY);
                }
            }
        } catch (InterruptedException e) {
            logger.log(Level.FINE, "Read aborted");
            return null;
        }
        logger.log(Level.FINE, "< ({})", line);
        return line.toString();
    }

    public int readBytes(byte[] buffer) throws IOException {
        int offset = 0;
        try {
            while (offset < buffer.length) {
                if (input.available() > 0) {
                    int size = input.read(buffer, offset, buffer.length - offset);
                    if (size < 0) {
                        break;
                    }
                    offset += size;
                } else {
                    /*
                     * Sleeping here allows us to be interruped (the serial is
                     * not by itself).
                     */
                    Thread.sleep(READ_DELAY);
                }
            }
        } catch (InterruptedException e) {
            logger.log(Level.FINE, "Read aborted");
            return -1;
        }
        logger.log(Level.FINE, "< {} byte(s)", offset);
        return offset;
    }

    public void writeLine(String line) throws IOException {
        for (int i = 0; i < line.length(); ++i) {
            output.write(line.charAt(i));
        }
        output.flush();
        logger.log(Level.FINE, "> ({})", line);
    }

    @Override
    public void close() {
        if (serialPort != null) {
            try {
                output.flush();
            } catch (IOException e) {
                logger.log(Level.WARNING, "When flushing output before closing serial.", e);
            }
            serialPort.close();
            serialPort = null;
        }
    }
}
