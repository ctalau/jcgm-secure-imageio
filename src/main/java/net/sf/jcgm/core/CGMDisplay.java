package net.sf.jcgm.core;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;

/**
 * Renders a parsed {@link CGM} onto a {@link Graphics2D} context.
 * Handles coordinate transformation from VDC (Virtual Device Coordinates)
 * to the target image dimensions.
 */
public class CGMDisplay {

    private final CGM cgm;
    private Graphics2D g2d;
    private double scaleX = 1.0;
    private double scaleY = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;

    // Current drawing state
    private Color lineColor = Color.BLACK;
    private Color fillColor = Color.WHITE;
    private Color edgeColor = Color.BLACK;
    private BasicStroke lineStroke = new BasicStroke(1.0f);
    private boolean filled = false;
    private Color textColor = Color.BLACK;

    public CGMDisplay(CGM cgm) {
        this.cgm = cgm;
    }

    /**
     * Scales the display to fit within the given width and height.
     */
    public void scale(Graphics2D g2d, int width, int height) {
        this.g2d = g2d;
        int vdcWidth = cgm.getVdcWidth();
        int vdcHeight = cgm.getVdcHeight();
        if (vdcWidth == 0) vdcWidth = 32767;
        if (vdcHeight == 0) vdcHeight = 32767;

        scaleX = (double) width / vdcWidth;
        scaleY = (double) height / vdcHeight;
        offsetX = -Math.min(cgm.getVdcExtentX1(), cgm.getVdcExtentX2()) * scaleX;
        offsetY = -Math.min(cgm.getVdcExtentY1(), cgm.getVdcExtentY2()) * scaleY;
    }

    /**
     * Paints all CGM commands onto the Graphics2D context.
     */
    public void paint(Graphics2D g2d) {
        if (this.g2d == null) {
            this.g2d = g2d;
        }

        for (CGM.Command cmd : cgm.getCommands()) {
            try {
                processCommand(cmd);
            } catch (Exception e) {
                // Skip malformed commands
            }
        }
    }

    private void processCommand(CGM.Command cmd) {
        int cls = cmd.getElementClass();
        int id = cmd.getElementId();
        byte[] params = cmd.getParameters();

        switch (cls) {
            case 0 -> processDelimiterElement(id, params);
            case 2 -> processPictureDescriptor(id, params);
            case 4 -> processGraphicalPrimitive(id, params);
            case 5 -> processAttributeElement(id, params);
        }
    }

    private void processDelimiterElement(int id, byte[] params) {
        // Delimiter elements (BEGIN METAFILE, BEGIN PICTURE, etc.)
        // No rendering action needed
    }

    private void processPictureDescriptor(int id, byte[] params) {
        // Picture descriptor elements handled during parse
    }

    private void processGraphicalPrimitive(int id, byte[] params) {
        switch (id) {
            case 1 -> drawPolyline(params);      // POLYLINE
            case 2 -> drawDisjointPolyline(params); // DISJOINT POLYLINE
            case 3 -> drawPolymarker(params);    // POLYMARKER
            case 4 -> drawText(params);          // TEXT
            case 7 -> drawPolygon(params);       // POLYGON
            case 11 -> drawRectangle(params);    // RECTANGLE
            case 12 -> drawCircle(params);       // CIRCLE
            case 15 -> drawCircularArc3Point(params); // CIRCULAR ARC 3 POINT
            case 22 -> drawEllipse(params);      // ELLIPSE
        }
    }

    private void processAttributeElement(int id, byte[] params) {
        switch (id) {
            case 2 -> { // LINE TYPE
                if (params.length >= 2) {
                    int lineType = ((params[0] & 0xFF) << 8) | (params[1] & 0xFF);
                    float[] dash = switch (lineType) {
                        case 2 -> new float[]{6, 3};      // DASH
                        case 3 -> new float[]{1, 3};      // DOT
                        case 4 -> new float[]{6, 3, 1, 3}; // DASH-DOT
                        default -> null;                   // SOLID
                    };
                    if (dash != null) {
                        lineStroke = new BasicStroke(lineStroke.getLineWidth(),
                                BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, dash, 0);
                    } else {
                        lineStroke = new BasicStroke(lineStroke.getLineWidth());
                    }
                }
            }
            case 3 -> { // LINE WIDTH
                if (params.length >= 2) {
                    int w = ((params[0] & 0xFF) << 8) | (params[1] & 0xFF);
                    float width = Math.max(1, w * (float) scaleX);
                    lineStroke = new BasicStroke(width);
                }
            }
            case 4 -> { // LINE COLOUR
                lineColor = readColor(params);
            }
            case 14 -> { // FILL COLOUR
                fillColor = readColor(params);
                filled = true;
            }
            case 10 -> { // TEXT COLOUR
                textColor = readColor(params);
            }
        }
    }

    private void drawPolyline(byte[] params) {
        short[] coords = coordsFromBytes(params);
        if (coords.length < 4) return;

        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);

        Path2D.Double path = new Path2D.Double();
        path.moveTo(transformX(coords[0]), transformY(coords[1]));
        for (int i = 2; i < coords.length - 1; i += 2) {
            path.lineTo(transformX(coords[i]), transformY(coords[i + 1]));
        }
        g2d.draw(path);
    }

    private void drawDisjointPolyline(byte[] params) {
        short[] coords = coordsFromBytes(params);
        if (coords.length < 4) return;

        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);

        for (int i = 0; i < coords.length - 3; i += 4) {
            g2d.draw(new Line2D.Double(
                    transformX(coords[i]), transformY(coords[i + 1]),
                    transformX(coords[i + 2]), transformY(coords[i + 3])));
        }
    }

    private void drawPolymarker(byte[] params) {
        short[] coords = coordsFromBytes(params);
        g2d.setColor(lineColor);
        for (int i = 0; i < coords.length - 1; i += 2) {
            double x = transformX(coords[i]);
            double y = transformY(coords[i + 1]);
            g2d.fill(new Ellipse2D.Double(x - 2, y - 2, 4, 4));
        }
    }

    private void drawText(byte[] params) {
        if (params.length < 6) return;
        short x = (short) (((params[0] & 0xFF) << 8) | (params[1] & 0xFF));
        short y = (short) (((params[2] & 0xFF) << 8) | (params[3] & 0xFF));
        // params[4..5] = final/not-final flag
        // params[6..] = text string
        if (params.length > 6) {
            int strLen = params[6] & 0xFF;
            if (params.length >= 7 + strLen) {
                String text = new String(params, 7, strLen);
                g2d.setColor(textColor);
                g2d.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g2d.drawString(text, (float) transformX(x), (float) transformY(y));
            }
        }
    }

    private void drawPolygon(byte[] params) {
        short[] coords = coordsFromBytes(params);
        if (coords.length < 6) return;

        Path2D.Double path = new Path2D.Double();
        path.moveTo(transformX(coords[0]), transformY(coords[1]));
        for (int i = 2; i < coords.length - 1; i += 2) {
            path.lineTo(transformX(coords[i]), transformY(coords[i + 1]));
        }
        path.closePath();

        if (filled) {
            g2d.setColor(fillColor);
            g2d.fill(path);
        }
        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);
        g2d.draw(path);
    }

    private void drawRectangle(byte[] params) {
        if (params.length < 8) return;
        short[] coords = coordsFromBytes(params);
        double x1 = transformX(coords[0]);
        double y1 = transformY(coords[1]);
        double x2 = transformX(coords[2]);
        double y2 = transformY(coords[3]);

        Rectangle2D.Double rect = new Rectangle2D.Double(
                Math.min(x1, x2), Math.min(y1, y2),
                Math.abs(x2 - x1), Math.abs(y2 - y1));

        if (filled) {
            g2d.setColor(fillColor);
            g2d.fill(rect);
        }
        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);
        g2d.draw(rect);
    }

    private void drawCircle(byte[] params) {
        if (params.length < 6) return;
        short[] coords = coordsFromBytes(params);
        double cx = transformX(coords[0]);
        double cy = transformY(coords[1]);
        double r = Math.abs(coords[2] * scaleX);

        Ellipse2D.Double circle = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
        if (filled) {
            g2d.setColor(fillColor);
            g2d.fill(circle);
        }
        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);
        g2d.draw(circle);
    }

    private void drawCircularArc3Point(byte[] params) {
        if (params.length < 12) return;
        short[] coords = coordsFromBytes(params);
        // Simplified: draw as polyline through 3 points
        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(transformX(coords[0]), transformY(coords[1]));
        path.lineTo(transformX(coords[2]), transformY(coords[3]));
        path.lineTo(transformX(coords[4]), transformY(coords[5]));
        g2d.draw(path);
    }

    private void drawEllipse(byte[] params) {
        if (params.length < 12) return;
        short[] coords = coordsFromBytes(params);
        double cx = transformX(coords[0]);
        double cy = transformY(coords[1]);
        // Conjugate diameter endpoints
        double dx1 = Math.abs(transformX(coords[2]) - cx);
        double dy1 = Math.abs(transformY(coords[3]) - cy);
        double dx2 = Math.abs(transformX(coords[4]) - cx);
        double dy2 = Math.abs(transformY(coords[5]) - cy);
        double rx = Math.max(dx1, dx2);
        double ry = Math.max(dy1, dy2);
        if (rx == 0) rx = 1;
        if (ry == 0) ry = 1;

        Ellipse2D.Double ellipse = new Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry);
        if (filled) {
            g2d.setColor(fillColor);
            g2d.fill(ellipse);
        }
        g2d.setColor(lineColor);
        g2d.setStroke(lineStroke);
        g2d.draw(ellipse);
    }

    private double transformX(short x) {
        return x * scaleX + offsetX;
    }

    private double transformY(short y) {
        return y * scaleY + offsetY;
    }

    private short[] coordsFromBytes(byte[] params) {
        int count = params.length / 2;
        short[] coords = new short[count];
        for (int i = 0; i < count; i++) {
            coords[i] = (short) (((params[i * 2] & 0xFF) << 8) | (params[i * 2 + 1] & 0xFF));
        }
        return coords;
    }

    private Color readColor(byte[] params) {
        if (params.length >= 6) {
            // Direct colour (RGB, 16-bit per component)
            int r = ((params[0] & 0xFF) << 8) | (params[1] & 0xFF);
            int g = ((params[2] & 0xFF) << 8) | (params[3] & 0xFF);
            int b = ((params[4] & 0xFF) << 8) | (params[5] & 0xFF);
            return new Color(r >> 8, g >> 8, b >> 8);
        } else if (params.length >= 3) {
            // Direct colour (RGB, 8-bit per component)
            return new Color(params[0] & 0xFF, params[1] & 0xFF, params[2] & 0xFF);
        } else if (params.length >= 2) {
            // Indexed colour
            int idx = ((params[0] & 0xFF) << 8) | (params[1] & 0xFF);
            return indexToColor(idx);
        }
        return Color.BLACK;
    }

    private Color indexToColor(int index) {
        // Default color table
        return switch (index) {
            case 0 -> Color.WHITE;
            case 1 -> Color.BLACK;
            case 2 -> Color.RED;
            case 3 -> Color.GREEN;
            case 4 -> Color.BLUE;
            case 5 -> Color.YELLOW;
            case 6 -> Color.CYAN;
            case 7 -> Color.MAGENTA;
            default -> Color.BLACK;
        };
    }
}
