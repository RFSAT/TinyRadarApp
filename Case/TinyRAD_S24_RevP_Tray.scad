// TinyRAD Galaxy S24 Enclosure
// Rev P - Tray
// Derived from Rev O
// No geometry changes from Rev O

$fn=128;

pcb_x=85;
pcb_y=54;

clearance=1.6;
wall=1.6;
base_t=1.5;
inside_h=7.5;
corner_r=3;

outer_x=pcb_x + 2*(wall+clearance);
outer_y=pcb_y + 2*(wall+clearance);

module rounded2d(x,y,r){
    offset(r=r) offset(delta=-r) square([x,y]);
}

module tray(){

    union(){

        difference(){

            linear_extrude(base_t+inside_h)
                rounded2d(outer_x,outer_y,corner_r);

            translate([wall,wall,base_t])
                cube([outer_x-2*wall,outer_y-2*wall,20]);

            // USB-C
            translate([outer_x/2-5,-0.1,2.5])
                cube([10,4,5]);

            // AUX
            translate([
                wall + clearance + 19 - 7.2,
                wall + clearance + 51 - 3.2,
                -0.1
            ])
            cube([14.4,6.4,base_t+0.3]);

            // MagSafe recess
            translate([outer_x/2 + 16, outer_y/2, 0.6])
            difference(){
                cylinder(d=55.5,h=1.2);
                cylinder(d=44.5,h=1.3);
            }

            // Snap groove
            translate([1.2,1.2,base_t+inside_h-1.2])
                cube([outer_x-2.4,outer_y-2.4,1.3]);
        }

        for(x=[wall+clearance+3, wall+clearance+pcb_x-3])
        for(y=[wall+clearance+3, wall+clearance+pcb_y-3])
        translate([x,y,base_t])
        difference(){
            cylinder(d=7,h=5);
            cylinder(d=2.7,h=6);
        }
    }
}

tray();