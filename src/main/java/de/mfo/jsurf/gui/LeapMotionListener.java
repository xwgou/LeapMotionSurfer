package de.mfo.jsurf.gui;

import java.awt.Point;

import com.leapmotion.leap.Controller;
import com.leapmotion.leap.Frame;
import com.leapmotion.leap.Hand;
import com.leapmotion.leap.HandList;
import com.leapmotion.leap.Listener;

public class LeapMotionListener extends Listener {
	private JSurferRenderPanel panel;
	private long lastFrameID = 0;
	private static int offset = 5;
	
	public LeapMotionListener (JSurferRenderPanel panel) {
		this.panel = panel;
	}
	
	@Override
	public void onConnect(Controller controller) {
		System.out.println("Connected");
//		controller.enableGesture(Gesture.Type.TYPE_SWIPE);
//		controller.enableGesture(Gesture.Type.TYPE_CIRCLE);
//		controller.enableGesture(Gesture.Type.TYPE_SCREEN_TAP);
//		controller.enableGesture(Gesture.Type.TYPE_KEY_TAP);System.out.println("Connected!\n");
	}
	
	@Override
	public void onExit(Controller arg0) {
		System.out.println("Exit!\n");
	}
	
	@Override
	public void onDisconnect(Controller arg0) {
		System.out.println("Disconnected!\n");
	}
	
	@Override
	public void onInit(Controller arg0) {
		System.out.println("Initialized!\n");
	}
	
	@Override
	public void onFrame(Controller controller) {
		Frame frame = controller.frame();
		HandList hands;
        Hand firstHand;
		hands = frame.hands();
		if (frame.id() == lastFrameID) return;
		
		lastFrameID = frame.id();
		if (hands == null || hands.isEmpty()) return;
        
		firstHand = hands.get(0);
		
		if (firstHand == null || !firstHand.isValid() || firstHand.fingers().count() <= 1) {
			System.out.println("no fingers");
			return;
		}
		
        int x = (int)firstHand.palmPosition().getX()*LeapMotionListener.offset;
        int y = (int)firstHand.palmPosition().getY()*LeapMotionListener.offset;
        int z = (int)firstHand.palmVelocity().getZ()%LeapMotionListener.offset;
        
        System.out.println("dragging: x = " + x + " || y = " + y + " || z = " + z);
		Point p = new Point(x, y);
		panel.rsd.dragTo(p);
		
		panel.scheduleSurfaceRepaint();
		panel.scaleSurface(z);
	}

}
