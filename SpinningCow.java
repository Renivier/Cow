import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.IntStream;

/*
 * Spinning ascii cow.
 *
 * donut.c scatters points over a surface and plots where they land. A cow has
 * too many separate parts for that to look right, so this works backwards: every
 * character on the screen fires its own ray out of the camera, and the ray
 * walks through a function that describes the cow ("how far is this point from
 * the nearest bit of cow?") until it touches the surface. That surface point
 * gets lit, and its brightness decides which character is printed.
 *
 * options (all optional):
 *   -w N / -h N    frame size in characters. missing ones come from the terminal,
 *                  or 140x56 if the terminal can't be measured
 *   -s N           rays per axis inside one character, 1 is fast, 2 is smoother
 *   -c             colour output
 *   --noground     skip the floor shadow
 *   --still        print one frame and quit      (--angle D sets the turn in degrees)
 *   --bench N      time N frames and quit
 */
public class SpinningCow {

    // characters from empty to dense. brightness 0..1 picks a position in this string
    static final String RAMP = " .'`^\",:;Il!i><~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";

    // how far the camera is from the cow. bigger = smaller cow on screen
    static final double CAM_DIST = 5.6;

    // height of the bottom of the hooves. the invisible floor is at this height
    static final double GROUND = -1.145;

    // the cow is modelled with the body around (0,0,0), but the head sticks out a
    // lot further than the tail does. spinning around the origin would make the
    // cow swing sideways, so it spins around the middle of the whole animal instead
    static final double CX = 0.30, CY = -0.15, CZ = 0.0;

    // radius of a sphere around that middle point which contains the whole cow.
    // a ray that misses the sphere can't touch the cow, so it isn't marched at all
    static final double BOUND_R = 2.0;

    // ids for the materials, used as indexes into the tables below
    static final int FUR = 0, SPOT = 1, EYE = 2, NOSE = 3, HOOF = 4, HORN = 5, UDDER = 6, TUFT = 7, GRASS = 8;

    // base colour of each material, r g b in 0..1. brightness comes from these too
    // (see shadeCow) so dark materials end up as sparse characters
    static final double[][] ALB = {
        {0.96, 0.95, 0.92}, // fur
        {0.38, 0.38, 0.40}, // spot, the dark patches
        {0.04, 0.03, 0.03}, // eye
        {0.95, 0.62, 0.62}, // nose
        {0.24, 0.20, 0.18}, // hoof
        {0.92, 0.88, 0.72}, // horn
        {0.97, 0.68, 0.68}, // udder
        {0.14, 0.12, 0.12}, // tail tuft
        {0.35, 0.65, 0.30}, // ground
    };

    // how strong the shiny highlight is for each material. the eye is high so it gets a glint
    static final double[] SPEC = {0.10, 0.10, 1.20, 0.35, 0.30, 0.40, 0.20, 0.05, 0.0};

    // how tight that highlight is. a bigger number gives a smaller, sharper dot
    static final double[] SHINE = {20, 20, 90, 30, 30, 30, 30, 10, 1};

    // the dark patches are made from these spheres, x y z radius in model space.
    // wherever several of them overlap the fur is dark (see spotCoverage)
    static final double[][] BLOBS = {
        { 0.25,  0.20,  0.50, 0.50}, {-0.55, -0.02,  0.50, 0.38},
        { 0.60,  0.00, -0.50, 0.42}, {-0.25,  0.30, -0.50, 0.50},
        {-0.20,  0.72,  0.05, 0.38}, {-0.95,  0.35, -0.15, 0.30},
        { 0.95,  0.55,  0.30, 0.28}, { 1.25,  0.35, -0.25, 0.34},
        { 1.62,  0.50,  0.17, 0.17}, { 0.90,  0.55, -0.30, 0.20},
        { 0.10, -0.40,  0.30, 0.25}, { 0.66, -0.35,  0.22, 0.15},
        {-0.70, -0.30, -0.25, 0.15},
    };

    // W x H is the size of the frame in characters. SS is the number of rays per
    // axis per character (2 means 4 rays for every character)
    static int W, H, SS;
    static boolean COLOR, GROUND_ON = true;

    // how wide the camera's view is. set in main once W and H are known
    static double FOV;

    // everything below is worked out once per frame in setupFrame(), before the
    // threads start. the threads only read it, so there's nothing to lock
    static double ca, sa, camFy, camFz, camUy, camUz;   // cos/sin of the cow's turn, camera forward and up vectors
    static double roX, roY, roZ;      // where the camera is, measured in the cow's own coordinates
    static double lx, ly, lz;         // direction towards the light, in the cow's own coordinates
    static double tailSway;           // how far the tail tip is swung to the side right now

    // one entry per character: brightness, and the average material colour
    static float[] cellI, cellR, cellG, cellB;

    // distance functions for simple shapes. each one takes a point and returns how
    // far that point is from the shape. the number is negative inside the shape

    // plain length of a 3d vector
    static double len(double x, double y, double z) { return Math.sqrt(x * x + y * y + z * z); }

    // smooth minimum. min(a, b) is the union of two shapes, but it leaves a sharp
    // crease where they meet. this rounds the crease off over a width of k. it's
    // the reason the legs and neck melt into the body
    static double smin(double a, double b, double k) {
        // 1 when a and b are equal (right on the crease), 0 once they are k apart
        double h = Math.max(k - Math.abs(a - b), 0.0) / k;
        // take the closer shape, then pull the surface out a bit near the crease to fill it in
        return Math.min(a, b) - h * h * k * 0.25;
    }

    // smoothstep. 0 below e0, 1 above e1, and an s shaped curve in between
    static double smooth(double e0, double e1, double x) {
        // how far x is between e0 and e1, cut off at 0 and 1
        double t = Math.max(0.0, Math.min(1.0, (x - e0) / (e1 - e0)));
        // 3t^2 - 2t^3, flat at both ends so there is no visible edge
        return t * t * (3.0 - 2.0 * t);
    }

    // ellipsoid with radii rx, ry, rz. the exact distance to an ellipsoid has no
    // cheap formula, so this is the usual approximation. it is exact on the
    // surface and drifts a bit further away, which the 0.9 in trace() covers
    static double sdEllipsoid(double x, double y, double z, double rx, double ry, double rz) {
        // length of the point after squashing the ellipsoid into a unit sphere. 1 = on the surface
        double k0 = len(x / rx, y / ry, z / rz);
        // the same, divided by the radii once more. this is the slope of k0, used to turn it into a distance
        double k1 = len(x / (rx * rx), y / (ry * ry), z / (rz * rz));
        // k1 is only 0 at the exact centre. return a negative number there so we don't divide by 0
        return k1 < 1e-9 ? -Math.min(rx, Math.min(ry, rz)) : k0 * (k0 - 1.0) / k1;
    }

    // box with half sizes bx, by, bz and rounded edges (radius r). the body is one
    // of these, a plain box would look like a suitcase
    static double sdRoundBox(double x, double y, double z, double bx, double by, double bz, double r) {
        // how far the point is outside the box on each axis, negative if inside
        double qx = Math.abs(x) - bx, qy = Math.abs(y) - by, qz = Math.abs(z) - bz;
        // outside the box, the part that sticks out. 0 on any axis where it's inside
        double ox = Math.max(qx, 0), oy = Math.max(qy, 0), oz = Math.max(qz, 0);
        // outside: straight line to the box. inside: the (negative) distance to the nearest wall.
        // the - r grows the whole box outwards by r, and that is what rounds the corners
        return len(ox, oy, oz) + Math.min(Math.max(qx, Math.max(qy, qz)), 0.0) - r;
    }

    // capsule from (x0,y0,z0) to (x1,y1,z1) whose radius goes from r1 at the start
    // to r2 at the end. legs, neck, horns and tail are all this. the distance isn't
    // exact when the two radii are very different, the 0.9 in trace() covers that
    static double sdTaper(double px, double py, double pz,
                          double x0, double y0, double z0, double x1, double y1, double z1,
                          double r1, double r2) {
        // pa goes from the start of the segment to the point, ba is the segment itself
        double pax = px - x0, pay = py - y0, paz = pz - z0;
        double bax = x1 - x0, bay = y1 - y0, baz = z1 - z0;
        // how far along the segment the point lands when projected onto it, 0 = start, 1 = end
        double h = (pax * bax + pay * bay + paz * baz) / (bax * bax + bay * bay + baz * baz);
        // points beyond the ends are measured to the end point instead
        h = h < 0 ? 0 : (h > 1 ? 1 : h);
        // distance to the closest spot on the segment, minus the radius at that spot
        return len(pax - bax * h, pay - bay * h, paz - baz * h) - (r1 + (r2 - r1) * h);
    }

    // the cow's parts. the cow is the same on the left and the right, so these take
    // az = |z| and only describe one side. the other side comes for free

    static double dHorn(double x, double y, double az) {
        // a thin cone leaning up and outwards from the top of the head
        return sdTaper(x, y, az, 1.50, 0.50, 0.12, 1.50, 0.74, 0.21, 0.045, 0.015);
    }

    static double dEye(double x, double y, double az) {
        // a small sphere, centre pushed just inside the head so only part of it shows
        return len(x - 1.70, y - 0.37, az - 0.15) - 0.04;
    }

    static double dUdder(double x, double y, double az) {
        // early out. far away from the udder just return the distance to a sphere
        // around it, which can't be bigger than the real distance. 0.3 is more than
        // any blend width used on it, so skipping the exact shape changes nothing
        double bound = len(x + 0.30, y + 0.60, az) - 0.5;
        if (bound > 0.3) return bound;
        // the udder itself
        double u = sdEllipsoid(x + 0.30, y + 0.52, az, 0.20, 0.14, 0.17);
        // two rows of teats at different x. mirroring z makes it four
        double t1 = sdTaper(x, y, az, -0.22, -0.60, 0.075, -0.22, -0.73, 0.075, 0.032, 0.022);
        double t2 = sdTaper(x, y, az, -0.38, -0.60, 0.075, -0.38, -0.73, 0.075, 0.032, 0.022);
        return smin(smin(u, t1, 0.05), t2, 0.05);
    }

    // the dark tuft at the end of the tail. it uses the real z because the tail swings
    static double dTuft(double x, double y, double z) {
        return sdEllipsoid(x + 1.14, y + 0.57, z - tailSway, 0.065, 0.17, 0.065);
    }

    static double dTail(double x, double y, double z) {
        // same early out as the udder
        double bound = len(x + 1.15, y + 0.10, z) - 0.85;
        if (bound > 0.3) return bound;
        double s = tailSway;
        // the tail is two segments, root to a middle point and middle point to the tip.
        // the middle moves half as far sideways as the tip so the tail bends into an arc
        double bx = -1.13, by = 0.12, bz = 0.5 * s, cx = -1.14, cy = -0.42, cz = s;
        double t1 = sdTaper(x, y, z, -1.02, 0.50, 0.0, bx, by, bz, 0.045, 0.034);
        double t2 = sdTaper(x, y, z, bx, by, bz, cx, cy, cz, 0.034, 0.027);
        return smin(smin(t1, t2, 0.03), dTuft(x, y, z), 0.05);
    }

    // the whole cow as one distance function. the renderer calls this tens of
    // millions of times per second, so it creates no objects
    static double map(double x, double y, double z) {
        // only the z > 0 side is modelled, the other side is the mirror image
        double az = Math.abs(z);

        // body: a rounded box. half sizes 0.80, 0.32, 0.22 plus 0.22 rounding make it
        // 2.04 long, 1.08 tall and 0.88 wide. the y - 0.10 lifts it a bit above the origin
        double d = sdRoundBox(x, y - 0.10, az, 0.80, 0.32, 0.22, 0.22);
        // belly: an ellipsoid under the box so the underside sags
        d = smin(d, sdEllipsoid(x - 0.05, y + 0.20, az, 0.70, 0.30, 0.40), 0.20);
        // the bump above the front legs, where the neck starts
        d = smin(d, sdEllipsoid(x - 0.55, y - 0.45, az, 0.35, 0.18, 0.22), 0.18);
        // neck: thick at the shoulders, thinner where it meets the head
        d = smin(d, sdTaper(x, y, az, 0.75, 0.30, 0.0, 1.45, 0.34, 0.0, 0.36, 0.20), 0.15);

        // the head and everything on it are one group, skipped when the point is far
        // away from the head. same sphere trick as the udder
        double head;
        double dhs = len(x - 1.62, y - 0.32, az) - 0.78;
        if (dhs > 0.3) {
            head = dhs;
        } else {
            // skull
            head = sdEllipsoid(x - 1.58, y - 0.30, az, 0.30, 0.22, 0.19);
            // muzzle, a smaller ellipsoid stuck on the front of the skull
            head = smin(head, sdEllipsoid(x - 1.86, y - 0.21, az, 0.20, 0.14, 0.15), 0.10);
            // ear, a flat thin ellipsoid sticking out sideways
            head = smin(head, sdEllipsoid(x - 1.47, y - 0.46, az - 0.24, 0.08, 0.035, 0.13), 0.04);
            head = smin(head, dHorn(x, y, az), 0.03);
            // eye, blended a little so it bulges out of the head
            head = smin(head, dEye(x, y, az), 0.02);
        }
        d = smin(d, head, 0.07);

        // the legs are a group as well. each leg is three pieces: upper, lower, hoof
        double legs;
        double dl = len(x, y + 0.55, az) - 1.2;
        if (dl > 0.3) {
            legs = dl;
        } else {
            // front leg: almost straight, thick at the top
            double fu = sdTaper(x, y, az, 0.65, 0.00, 0.22, 0.66, -0.55, 0.21, 0.17, 0.095);
            double fl = sdTaper(x, y, az, 0.66, -0.55, 0.21, 0.66, -0.95, 0.21, 0.085, 0.065);
            double fh = sdTaper(x, y, az, 0.66, -0.93, 0.21, 0.67, -1.05, 0.215, 0.075, 0.095);
            // rear leg: a big thigh sloping backwards, then the lower leg angles forward again
            double ru = sdTaper(x, y, az, -0.62, 0.05, 0.27, -0.76, -0.50, 0.25, 0.22, 0.11);
            double rl = sdTaper(x, y, az, -0.76, -0.50, 0.25, -0.66, -0.95, 0.23, 0.085, 0.065);
            double rh = sdTaper(x, y, az, -0.66, -0.93, 0.23, -0.66, -1.05, 0.23, 0.075, 0.095);
            // join the three pieces of each leg smoothly
            double front = smin(smin(fu, fl, 0.05), fh, 0.03);
            double rear = smin(smin(ru, rl, 0.05), rh, 0.03);
            // front and rear legs are far apart, a plain min is enough
            legs = Math.min(front, rear);
        }
        d = smin(d, legs, 0.08);

        d = smin(d, dUdder(x, y, az), 0.08);
        // the tail gets the real z, it isn't the same on both sides
        d = smin(d, dTail(x, y, z), 0.05);
        return d;
    }

    // which material a point on the surface is made of. map() only returns a
    // distance, so this checks the small parts again and falls back to fur.
    // it runs once per pixel, at the hit point, so it can afford to be slow
    static int material(double x, double y, double z) {
        double az = Math.abs(z);
        // within 0.012 of a part's surface counts as being on that part
        if (dEye(x, y, az) < 0.012) return EYE;
        if (dHorn(x, y, az) < 0.012) return HORN;
        // the position test stops the bottom of the belly being taken for udder
        if (y < -0.45 && x > -0.55 && x < -0.05 && dUdder(x, y, az) < 0.012) return UDDER;
        if (dTuft(x, y, z) < 0.012) return TUFT;
        // hooves: low enough, and close to the x of a front or a rear leg
        if (y < -0.90 && (Math.abs(x - 0.66) < 0.15 || Math.abs(x + 0.66) < 0.15)) return HOOF;
        // nose: the front end of the muzzle
        if (x > 1.80 && sdEllipsoid(x - 1.86, y - 0.21, az, 0.20, 0.14, 0.15) < 0.012) return NOSE;
        return FUR;
    }

    // the spot pattern needs random looking numbers that come out the same every
    // time for the same point. that is value noise on top of an integer hash

    // turns a point on the integer grid into a fixed pseudo random number in 0..1
    static double hash(int x, int y, int z) {
        // multiply each coordinate by a different big odd number and xor the results,
        // so neighbouring grid points don't give related values
        int h = x * 374761393 ^ y * 668265263 ^ z * 1274126177;
        // shift, xor and multiply to scramble the bits some more
        h = (h ^ (h >>> 13)) * 1103515245;
        h ^= h >>> 16;
        // keep the low 16 bits and scale them to 0..1
        return (h & 0xFFFF) / 65535.0;
    }

    // smooth noise: a random value on every integer grid point, blended in between
    static double noise(double x, double y, double z) {
        // the grid cell the point is in, and where inside the cell it is (0..1 per axis)
        double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
        int xi = (int) fx, yi = (int) fy, zi = (int) fz;
        double tx = x - fx, ty = y - fy, tz = z - fz;
        // same curve as smooth(). without it the cell borders show up as creases
        tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty); tz = tz * tz * (3 - 2 * tz);
        // the random value at each of the 8 corners of the cell
        double a = hash(xi, yi, zi), b = hash(xi + 1, yi, zi), c = hash(xi, yi + 1, zi), d = hash(xi + 1, yi + 1, zi);
        double e = hash(xi, yi, zi + 1), f = hash(xi + 1, yi, zi + 1), g = hash(xi, yi + 1, zi + 1), h = hash(xi + 1, yi + 1, zi + 1);
        // blend along x on four edges of the cell, then along y, then along z
        double x0 = a + (b - a) * tx, x1 = c + (d - c) * tx, x2 = e + (f - e) * tx, x3 = g + (h - g) * tx;
        double y0 = x0 + (x1 - x0) * ty, y1 = x2 + (x3 - x2) * ty;
        return y0 + (y1 - y0) * tz;
    }

    // how much of a spot a point is, 0 = clean fur, 1 = fully dark
    static double spotCoverage(double x, double y, double z) {
        // push the point around a little with noise before testing it against the blobs.
        // the first noise (frequency 2.7) makes the outlines wobble, the second one
        // (frequency 8) makes them rough. the +1.3, +5.1 and so on only make x, y and z
        // use different parts of the noise, otherwise they'd all be shifted the same way
        double wx = x + 0.30 * (noise(x * 2.7 + 1.3, y * 2.7, z * 2.7) - 0.5) + 0.08 * (noise(x * 8.0, y * 8.0, z * 8.0) - 0.5);
        double wy = y + 0.30 * (noise(x * 2.7, y * 2.7 + 5.1, z * 2.7) - 0.5) + 0.08 * (noise(x * 8.0 + 3, y * 8.0, z * 8.0) - 0.5);
        double wz = z + 0.30 * (noise(x * 2.7, y * 2.7, z * 2.7 + 9.7) - 0.5) + 0.08 * (noise(x * 8.0, y * 8.0 + 7, z * 8.0) - 0.5);
        // every blob adds a bump that is 1 at its centre and fades with distance
        double v = 0;
        for (double[] b : BLOBS) {
            double dx = wx - b[0], dy = wy - b[1], dz = wz - b[2];
            v += Math.exp(-(dx * dx + dy * dy + dz * dz) / (b[3] * b[3]));
        }
        // cut the total at a threshold. it's a soft cut so the edges don't look jagged.
        // raise the two numbers for fewer and smaller spots, lower them for more
        return smooth(0.62, 0.78, v);
    }

    // soft shadow. walk from the surface towards the light. if the distance function
    // gets small on the way, something is in the way. how small, compared to how far
    // along we are, decides how dark the edge of the shadow is.
    // returns 1 when fully lit, 0 when fully in shadow
    static double softShadow(double ox, double oy, double oz) {
        // start a little way off the surface so it doesn't shadow itself
        double res = 1.0, t = 0.02;
        for (int i = 0; i < 28; i++) {
            double h = map(ox + lx * t, oy + ly * t, oz + lz * t);
            // being close to something shortly after leaving the surface means a dark
            // shadow, close only far away means a light one. the 8 sets how wide the soft edge is
            res = Math.min(res, 8.0 * h / t);
            // step forward by the distance, but never less than 0.02 so it can't get stuck
            t += Math.max(h, 0.02);
            // fully in shadow already, or far past the cow
            if (res < 0.01 || t > 4.0) break;
        }
        res = Math.max(0.0, Math.min(1.0, res));
        // smoothstep to soften the falloff
        return res * res * (3.0 - 2.0 * res);
    }

    // works out brightness and colour of a point on the cow.
    // (px,py,pz) is where the ray hit, (rx,ry,rz) the direction it was travelling.
    // results go into out: out[0] is brightness, out[1..3] the material colour
    static void shadeCow(double px, double py, double pz, double rx, double ry, double rz, double[] out) {
        // surface normal. the gradient of the distance function points straight away
        // from the surface. the usual way takes 6 samples, this takes 4, at the
        // corners of a tetrahedron, and the signs below combine them into the same gradient
        double h = 0.0015;
        double f1 = map(px + h, py - h, pz - h), f2 = map(px - h, py - h, pz + h);
        double f3 = map(px - h, py + h, pz - h), f4 = map(px + h, py + h, pz + h);
        double nx = f1 - f2 - f3 + f4, ny = -f1 - f2 + f3 + f4, nz = -f1 + f2 - f3 + f4;
        // scale it to length 1. a zero gradient shouldn't happen, but point up if it does
        double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (nl < 1e-12) { nx = 0; ny = 1; nz = 0; } else { nx /= nl; ny /= nl; nz /= nl; }

        // material colour. on fur it is mixed towards the spot colour by the spot amount
        int mat = material(px, py, pz);
        double ar, ag, ab;
        if (mat == FUR) {
            double c = spotCoverage(px, py, pz);
            ar = ALB[FUR][0] + (ALB[SPOT][0] - ALB[FUR][0]) * c;
            ag = ALB[FUR][1] + (ALB[SPOT][1] - ALB[FUR][1]) * c;
            ab = ALB[FUR][2] + (ALB[SPOT][2] - ALB[FUR][2]) * c;
        } else {
            ar = ALB[mat][0]; ag = ALB[mat][1]; ab = ALB[mat][2];
        }

        // diffuse light. normal dot light direction is 1 when the surface faces the
        // light, 0 when it's edge on, negative when it faces away
        double ndl = nx * lx + ny * ly + nz * lz;
        // is anything between this point and the light? the start is moved out along
        // the normal a bit, same reason as the 0.02 inside softShadow
        double sh = softShadow(px + nx * 0.012, py + ny * 0.012, pz + nz * 0.012);
        double diff = Math.max(ndl, 0.0) * sh;

        // ambient occlusion. look at the distance function at a few points along the
        // normal. on open surface the distance is the same as how far out we went.
        // in a crease (armpit, under the belly) it is smaller, and that shortfall
        // makes the point darker. the further samples count for less
        double occ = 0, sca = 1;
        for (int i = 1; i <= 5; i++) {
            double hh = 0.015 + 0.075 * i;
            occ += (hh - map(px + nx * hh, py + ny * hh, pz + nz * hh)) * sca;
            sca *= 0.75;
        }
        double ao = Math.max(0.0, Math.min(1.0, 1.0 - 2.2 * occ));

        // specular highlight (blinn phong). v points back at the camera and hx,hy,hz
        // is the direction halfway between v and the light. when the surface normal
        // lines up with it you see the light reflected, and the power makes it a small dot
        double vx = -rx, vy = -ry, vz = -rz;
        double hx = lx + vx, hy = ly + vy, hz = lz + vz;
        double hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double ndh = Math.max(0.0, (nx * hx + ny * hy + nz * hz) / hl);
        double spec = SPEC[mat] * Math.pow(ndh, SHINE[mat]) * sh;
        // rim light. surfaces turned away from the camera get extra brightness, so
        // the outline of the cow doesn't disappear into the black background
        double rim = Math.pow(1.0 - Math.max(0.0, nx * vx + ny * vy + nz * vz), 3.0);
        // fake sky light, brighter on surfaces that face up (ny is 1 for up, -1 for down)
        double sky = 0.5 + 0.5 * ny;

        // add everything up: ambient (dimmed by occlusion), direct light, rim
        double lum = (0.10 + 0.16 * sky) * ao + 0.90 * diff + 0.18 * rim * ao * (0.3 + 0.7 * sky);
        // how bright the material colour looks. these are the standard weights, green counts most
        double albL = 0.299 * ar + 0.587 * ag + 0.114 * ab;
        out[0] = Math.min(1.0, albL * lum + spec);
        out[1] = ar; out[2] = ag; out[3] = ab;
    }

    // the floor exists only to show the shadow. (gx,gz) is where the ray crossed it
    static void shadeGround(double gx, double gz, double[] out) {
        // fade out away from the cow so the shadow doesn't run across the whole screen
        double rr = Math.hypot(gx - CX, gz - CZ);
        double fade = 1.0 - smooth(1.3, 2.4, rr);
        if (fade <= 0.001) return;
        // shadow cast by the cow, found the same way as the cow's own shadow
        double sh = softShadow(gx, GROUND + 0.012, gz);
        // contact shadow, darker where the cow is just above the floor (hooves, belly)
        double ao = Math.max(0.0, Math.min(1.0, map(gx, GROUND + 0.22, gz) / 0.22));
        double dark = Math.max(1.0 - sh, 0.9 * (1.0 - ao * ao));
        // a shadow here is printed as a visible character on an empty background, which is
        // backwards: the darker the shadow the brighter the character. at full strength
        // it was brighter than the cow, so it's kept very low
        out[0] = 0.10 * dark * fade;
        out[1] = ALB[GRASS][0]; out[2] = ALB[GRASS][1]; out[3] = ALB[GRASS][2];
    }

    // sets up everything that changes from frame to frame. t is the time in
    // seconds, yaw is how far the cow has turned
    static void setupFrame(double t, double yaw) {
        ca = Math.cos(yaw); sa = Math.sin(yaw);
        // the cow itself is never rotated. rotating it would mean rotating every point
        // map() looks at. turning the camera and the light the other way gives the
        // same picture and only has to be done once per frame

        // camera angle above the horizon in radians, bobbing a little over time
        double e = 0.30 + 0.05 * Math.sin(t * 0.45);
        double se = Math.sin(e), ce = Math.cos(e);
        // camera axes. forward looks down at the origin, up is at right angles to it.
        // right is always +x so it needs no variable
        camFy = -se; camFz = ce; camUy = ce; camUz = se;
        // camera position in the world, straight back from the origin and raised
        double wy = CAM_DIST * se, wz = -CAM_DIST * ce;
        // the same position in the cow's coordinates: undo the cow's turn, then add
        // the pivot offset so the pivot ends up in the middle of the view
        roX = -wz * sa + CX;
        roY = wy + CY;
        roZ = wz * ca + CZ;
        // light direction in the world: from the upper left, towards the camera side.
        // the y part is big so the light is high, a low light makes a shadow that
        // covers half the floor
        double Lx = -0.55, Ly = 1.30, Lz = -0.45;
        double ll = Math.sqrt(Lx * Lx + Ly * Ly + Lz * Lz);
        Lx /= ll; Ly /= ll; Lz /= ll;
        // same turn into cow coordinates as the camera got
        lx = Lx * ca - Lz * sa; ly = Ly; lz = Lx * sa + Lz * ca;
        // the tail swings left and right
        tailSway = 0.14 * Math.sin(t * 2.4);
    }

    // fires one ray through the point (u,v) of the frame. u is the column and v is
    // the row, both in character units and both can have a fraction (that's how
    // supersampling aims inside a character). result goes into out like in
    // shadeCow, and stays all zeros if the ray hits nothing
    static void trace(double u, double v, double[] out) {
        out[0] = out[1] = out[2] = out[3] = 0;
        // where the ray sits on the screen. rows run from -1 to 1 top to bottom. a
        // character is about twice as tall as it's wide, so one column is half a
        // row's step (1/H against 2/H), and that stops the cow looking stretched
        double sx = (u - W * 0.5) / H;
        double sy = (H * 0.5 - v) * 2.0 / H;
        // direction of the ray: forward, plus some sideways, plus some up
        double dxw = sx * FOV, dyw = camFy + camUy * sy * FOV, dzw = camFz + camUz * sy * FOV;
        // scale to length 1
        double il = 1.0 / Math.sqrt(dxw * dxw + dyw * dyw + dzw * dzw);
        dxw *= il; dyw *= il; dzw *= il;
        // turn the direction into cow coordinates, same as for the camera position
        double rx = dxw * ca - dzw * sa, ry = dyw, rz = dxw * sa + dzw * ca;

        // distance along the ray to the floor. a ray that points up or level never reaches it
        double tg = ry < -1e-6 ? (GROUND - roY) / ry : Double.POSITIVE_INFINITY;

        // ray against the bounding sphere. putting camera + t * direction into the sphere
        // equation gives a quadratic in t. disc below 0 means no solution, the ray misses
        double qx = roX - CX, qy = roY - CY, qz = roZ - CZ;
        double b = qx * rx + qy * ry + qz * rz;
        double c = qx * qx + qy * qy + qz * qz - BOUND_R * BOUND_R;
        double disc = b * b - c;
        double tHit = -1;
        if (disc > 0) {
            double sq = Math.sqrt(disc);
            // march only between where the ray enters the sphere and where it leaves it
            double t = Math.max(-b - sq, 0.0), tEnd = -b + sq;
            for (int i = 0; i < 110 && t < tEnd; i++) {
                double d = map(roX + rx * t, roY + ry * t, roZ + rz * t);
                // close enough to the surface counts as a hit
                if (d < 0.0011) { tHit = t; break; }
                // nothing is closer than d, so moving forward by d can't pass through
                // anything. 90% of it, because some of the distances above are approximate
                t += d * 0.9;
            }
        }
        // whatever is hit first along the ray wins, the cow or the floor
        if (tHit > 0 && tHit < tg) {
            shadeCow(roX + rx * tHit, roY + ry * tHit, roZ + rz * tHit, rx, ry, rz, out);
        } else if (GROUND_ON && tg < Double.POSITIVE_INFINITY) {
            shadeGround(roX + rx * tg, roZ + rz * tg, out);
        }
    }

    // renders the whole frame into the cell arrays
    static void render() {
        // rows don't depend on each other, so they're split across all cores
        IntStream.range(0, H).parallel().forEach(j -> {
            // scratch array for the results of trace(), one per row so threads don't share it
            double[] out = new double[4];
            double inv = 1.0 / (SS * SS);
            for (int i = 0; i < W; i++) {
                // total brightness, and total colour weighted by brightness
                double sI = 0, sr = 0, sg = 0, sb = 0;
                // SS x SS rays spread evenly over this character's square
                for (int a = 0; a < SS; a++) {
                    for (int b = 0; b < SS; b++) {
                        trace(i + (a + 0.5) / SS, j + (b + 0.5) / SS, out);
                        sI += out[0]; sr += out[1] * out[0]; sg += out[2] * out[0]; sb += out[3] * out[0];
                    }
                }
                int k = j * W + i;
                // average brightness. a character that's half cow comes out half as
                // bright, which softens the outline
                cellI[k] = (float) Math.min(1.0, sI * inv);
                // colour is averaged only over the rays that hit something, so the
                // empty ones don't dull it
                if (sI > 1e-6) { cellR[k] = (float) (sr / sI); cellG[k] = (float) (sg / sI); cellB[k] = (float) (sb / sI); }
                // nothing hit at all: the colour is never used because the character is a space
                else { cellR[k] = cellG[k] = cellB[k] = 1f; }
            }
        });
    }

    // squeezes a colour channel into 16 steps (0, 16, 32 ... 240). fewer different
    // colours means longer runs of the same one, so fewer escape codes to print
    static int q(double c) { return ((int) (Math.max(0.0, Math.min(1.0, c)) * 255)) & 0xF0; }

    // turns the cell arrays into the text to print
    static String frame() {
        StringBuilder sb = new StringBuilder(W * H * (COLOR ? 8 : 1) + H + 16);
        // cursor to the top left, so each frame is drawn over the last one
        sb.append("\033[H");
        // the colour written last, so a colour that didn't change isn't written again
        int lr = -1, lg = -1, lb = -1;
        int top = RAMP.length() - 1;
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                int k = j * W + i;
                double I = cellI[k];
                // brightness to character. the power of 0.8 lifts the dark end so dim
                // parts (shaded side, dark spots) still show some shape. the + 0.5 rounds
                char ch = RAMP.charAt((int) (Math.pow(I, 0.8) * top + 0.5));
                if (COLOR && ch != ' ') {
                    // the material colour, a bit dimmer on dim characters
                    double s = 0.45 + 0.55 * I;
                    int r = q(cellR[k] * s), g = q(cellG[k] * s), b = q(cellB[k] * s);
                    if (r != lr || g != lg || b != lb) {
                        // 24 bit foreground colour escape code
                        sb.append("\033[38;2;").append(r).append(';').append(g).append(';').append(b).append('m');
                        lr = r; lg = g; lb = b;
                    }
                }
                sb.append(ch);
            }
            // no newline after the last row, it would scroll the terminal up one line
            if (j < H - 1) sb.append('\n');
        }
        // back to the normal colour
        if (COLOR) sb.append("\033[0m");
        return sb.toString();
    }

    // asks the terminal how big it is. returns {columns, rows}, or null if that fails
    static int[] termSize() {
        try {
            // stty prints "rows cols". it's pointed at /dev/tty because our own stdin
            // might be a pipe. this works on linux, macOS and terminals running on them,
            // it fails on Windows and in IDE consoles that aren't a real terminal
            Process p = new ProcessBuilder("sh", "-c", "stty size </dev/tty").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] a = s.split("\\s+");
            // a[0] is rows and a[1] is columns, so they're swapped here
            return new int[]{Integer.parseInt(a[1]), Integer.parseInt(a[0])};
        } catch (Exception e) {
            // no sh, no tty, or the output wasn't two numbers
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        // 0 means the option wasn't given
        int w = 0, h = 0, ss = 0, bench = 0;
        boolean still = false;
        double angle = 25;
        // read the options. the ones that take a value read the next argument too (the ++i)
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-w": w = Integer.parseInt(args[++i]); break;
                case "-h": h = Integer.parseInt(args[++i]); break;
                case "-s": ss = Integer.parseInt(args[++i]); break;
                case "-c": COLOR = true; break;
                case "--noground": GROUND_ON = false; break;
                case "--still": still = true; break;
                case "--angle": angle = Double.parseDouble(args[++i]); break;
                case "--bench": bench = Integer.parseInt(args[++i]); break;
                default: System.err.println("unknown option " + args[i]); return;
            }
        }
        // pick the frame size. anything given on the command line wins, whatever is
        // missing comes from the terminal, and if that can't be measured it is 140x56
        if (w == 0 || h == 0) {
            int[] ts = termSize();
            // height is one row less than the terminal, a full last row makes it scroll
            if (ts != null) { if (w == 0) w = ts[0]; if (h == 0) h = ts[1] - 1; }
            else { if (w == 0) w = 140; if (h == 0) h = 56; }
        }
        // keep the size in a range that renders sensibly
        W = Math.max(40, Math.min(400, w));
        H = Math.max(20, Math.min(200, h));
        // two rays per axis if there are 4 or more cores to afford it, otherwise one
        SS = ss > 0 ? ss : (Runtime.getRuntime().availableProcessors() >= 4 ? 2 : 1);
        // the vertical view is fixed. when the frame is much taller than wide the
        // cow would be cut off at the sides, so the view is widened to fit it
        FOV = Math.max(0.30, 4.0 * H / (CAM_DIST * W));   // keep the whole cow in frame on tall terminals
        cellI = new float[W * H]; cellR = new float[W * H]; cellG = new float[W * H]; cellB = new float[W * H];

        if (bench > 0) {
            setupFrame(1.0, Math.toRadians(angle));
            // the first frame is slow while the JIT compiles, so it isn't timed
            render(); // warm-up
            long t0 = System.nanoTime();
            for (int i = 0; i < bench; i++) { setupFrame(1.0 + i * 0.03, i * 0.02); render(); }
            System.err.printf("%dx%d ss=%d: %.1f ms/frame%n", W, H, SS, (System.nanoTime() - t0) / 1e6 / bench);
            return;
        }
        if (still) {
            setupFrame(1.0, Math.toRadians(angle));
            render();
            System.out.println(frame());
            return;
        }

        // buffered, so a frame goes out in one write instead of one per character
        OutputStream os = new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 1 << 16);
        // when the program is stopped, reset the colour and show the cursor again
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { os.write("\033[0m\033[?25h\n".getBytes(StandardCharsets.US_ASCII)); os.flush(); } catch (Exception ignored) { }
        }));
        // clear the screen and hide the cursor
        os.write("\033[2J\033[?25l".getBytes(StandardCharsets.US_ASCII));
        long start = System.nanoTime();
        while (true) {
            long f0 = System.nanoTime();
            // the animation runs off the clock and not off a frame counter, so the
            // spin speed is the same whether the machine does 10 frames a second or 50
            double t = (f0 - start) / 1e9;
            // 0.6 radians a second is about one turn every 10.5 seconds
            setupFrame(t, t * 0.6);
            render();
            os.write(frame().getBytes(StandardCharsets.US_ASCII));
            os.flush();
            // aim for about 30 frames a second, sleep for whatever is left of 33 ms
            long spent = (System.nanoTime() - f0) / 1_000_000L;
            if (spent < 33) Thread.sleep(33 - spent);
        }
    }
}