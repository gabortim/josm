// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.imagery.vectortile.mapbox;

/**
 * Geometry types used by Mapbox Vector Tiles
 * @author Taylor Smock
 * @since xxx
 */
public enum GeometryTypes {
    /** May be ignored */
    UNKNOWN,
    /** May be a point or a multipoint geometry. Uses <i>only</i> {@link Command#MoveTo}. Multiple {@link Command#MoveTo}
     * indicates that it is a multi-point object. */
    POINT,
    /** May be a line or a multiline geometry. Each line {@link Command#MoveTo} and one or more {@link Command#LineTo}. */
    LINESTRING,
    /** May be a polygon or a multipolygon. Each ring uses a {@link Command#MoveTo}, one or more {@link Command#LineTo},
     * and one {@link Command#ClosePath} command. See {@link Ring}s. */
    POLYGON;

    /**
     * Rings used by {@link GeometryTypes#POLYGON}
     * @author Taylor Smock
     */
    public enum Ring {
        /** A ring that goes in the clockwise direction */
        ExteriorRing,
        /** A ring that goes in the anti-clockwise direction */
        InteriorRing
    }
}
