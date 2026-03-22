package net.sf.jcgm.core;

import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CGM (Computer Graphics Metafile) parser based on ISO 8632-3 binary encoding.
 * Parses the binary command stream into a list of {@link Command} objects that can be
 * rendered via {@link CGMDisplay}.
 */
public class CGM {

    private final List<Command> commands = new ArrayList<>();
    private int vdcExtentX1, vdcExtentY1, vdcExtentX2, vdcExtentY2;

    public CGM() {
    }

    public CGM(File cgmFile) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(cgmFile)))) {
            read(in);
        }
    }

    /**
     * Reads and parses a CGM binary stream.
     */
    public void read(DataInput in) throws IOException {
        boolean done = false;
        while (!done) {
            int header;
            try {
                header = in.readUnsignedShort();
            } catch (EOFException e) {
                break;
            }

            int elementClass = (header >> 12) & 0xF;
            int elementId = (header >> 5) & 0x7F;
            int paramLength = header & 0x1F;

            // Long form parameter list
            if (paramLength == 31) {
                paramLength = in.readUnsignedShort();
            }

            byte[] params = new byte[paramLength];
            in.readFully(params);

            // Skip padding byte for odd-length parameter lists
            if (paramLength % 2 != 0) {
                in.readByte();
            }

            Command cmd = new Command(elementClass, elementId, params);
            commands.add(cmd);

            // Extract VDC Extent (class=2, id=6)
            if (elementClass == 2 && elementId == 6 && paramLength >= 8) {
                DataInputStream pis = new DataInputStream(new ByteArrayInputStream(params));
                vdcExtentX1 = pis.readShort();
                vdcExtentY1 = pis.readShort();
                vdcExtentX2 = pis.readShort();
                vdcExtentY2 = pis.readShort();
            }

            // END METAFILE (class=0, id=2)
            if (elementClass == 0 && elementId == 2) {
                done = true;
            }
        }
    }

    public List<Command> getCommands() {
        return commands;
    }

    public int getVdcExtentX1() { return vdcExtentX1; }
    public int getVdcExtentY1() { return vdcExtentY1; }
    public int getVdcExtentX2() { return vdcExtentX2; }
    public int getVdcExtentY2() { return vdcExtentY2; }

    public int getVdcWidth() {
        return Math.abs(vdcExtentX2 - vdcExtentX1);
    }

    public int getVdcHeight() {
        return Math.abs(vdcExtentY2 - vdcExtentY1);
    }

    /**
     * Represents a single CGM command element.
     */
    public static class Command {
        private final int elementClass;
        private final int elementId;
        private final byte[] parameters;

        public Command(int elementClass, int elementId, byte[] parameters) {
            this.elementClass = elementClass;
            this.elementId = elementId;
            this.parameters = parameters;
        }

        public int getElementClass() { return elementClass; }
        public int getElementId() { return elementId; }
        public byte[] getParameters() { return parameters; }

        /**
         * Returns short[] of coordinate pairs from the parameter data.
         */
        public short[] getCoordinates() {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(parameters));
            List<Short> coords = new ArrayList<>();
            try {
                while (dis.available() >= 2) {
                    coords.add(dis.readShort());
                }
            } catch (IOException e) {
                // ignore trailing bytes
            }
            short[] result = new short[coords.size()];
            for (int i = 0; i < coords.size(); i++) {
                result[i] = coords.get(i);
            }
            return result;
        }
    }
}
