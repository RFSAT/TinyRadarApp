// TinyRAD Galaxy S24 Enclosure
// Rev P Lid
// Antenna window pockets with full-perimeter retention

$fn=128;

pcb_x=85;
pcb_y=54;

clearance=1.5;
wall=1.5;
corner_r=3;

outer_x=pcb_x + 2*(wall+clearance);
outer_y=pcb_y + 2*(wall+clearance);

module rounded2d(x,y,r)
{
    offset(r=r)
    offset(delta=-r)
    square([x,y]);
}

module window_frame(xpos)
{
    // Pocket frame
    translate([xpos-1.0, wall+clearance+4.0, 0])
    difference()
    {
        cube([32.0,45.0,0.6]);

        translate([0.1,0.1,-0.1])
            cube([31.8,44.8,0.8]);
    }
}

module lid()
{
    union()
    {
        difference()
        {
            linear_extrude(0.6)
                rounded2d(outer_x,outer_y,corner_r);

            // LEFT antenna aperture
            // translate([
            //     wall+clearance+3,
            //     wall+clearance+5,
            //     -1
            // ])
            // cube([30,43,6]);

            // RIGHT antenna aperture
            // translate([
            //     outer_x-(wall+clearance+3)-30,
            //     wall+clearance+5,
            //     -1
            // ])
            // cube([30,43,6]);
        }

        // Left antenna pocket
        window_frame(
            wall+clearance+3
        );

        // Right antenna pocket
        window_frame(
            outer_x-(wall+clearance+3)-30
        );

        // Reinforcement rib
        translate([outer_x/2-6,2,-1])
            cube([10,outer_y-4,1.6]);
    }

    // Snap tongue
    translate([1.6,1.6,-1.0])
    difference()
    {
        cube([outer_x-3.2,outer_y-3.2,1.0]);

        translate([1.25,1.25,-0.1])
            cube([outer_x-5.7,outer_y-5.7,1.2]);
    }
}

lid();