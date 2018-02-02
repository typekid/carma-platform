#!/usr/bin/env python
# license removed for brevity
import rospy
from std_msgs.msg import String
from cav_msgs.msg import ExternalObjectList
from cav_msgs.msg import Route
from geometry_msgs.msg import Pose
from geometry_msgs.msg import PoseArray
from geometry_msgs.msg import Transform

pub = rospy.Publisher('/external_pose', PoseArray, queue_size=10)
route_pub = rospy.Publisher('/route_pose', PoseArray, queue_size=10)

def external_objects_cb(obj):
    poses = []
    posesArray = PoseArray()
    for obj in obj.objects:
        posesArray.header.stamp = obj.header.stamp
        posesArray.header.frame_id = obj.header.frame_id
        poseStamped = Pose()
        poseStamped = obj.pose.pose
        poses.append(poseStamped)
    posesArray.poses = poses
    pub.publish(posesArray)
        #poses.append(poseStamped)

def route_cb(route):
    poses = []
    posesArray = PoseArray()
    pose = route.segments[0].pose
    poses.append(pose)
    for seg in route.segments:
        posesArray.header.frame_id = seg.pose.header.frame_id
        posesArray.header.stamp = seg.pose.header.stamp
        poses.append(seg.pose)
    posesArray.poses = poses
    route_pub.publish(posesArray)
    
def poseFromTransform(transform):
    pose = Pose()
    pose.position.x = transform.translation.x
    pose.position.y = transform.translation.y
    pose.position.y = transform.translation.z
    pose.orientation.x = transform.rotation.x
    pose.orientation.y = transform.rotation.y
    pose.orientation.z = transform.rotation.z
    pose.orientation.w = transform.rotation.w

    return pose
    
def echo_node():

    # In ROS, nodes are uniquely named. If two nodes with the same
    # node are launched, the previous one is kicked off. The
    # anonymous=True flag means that rospy will choose a unique
    # name for our 'listener' node so that multiple listeners can
    # run simultaneously.
    rospy.init_node('echo_node', anonymous=True)

    rospy.Subscriber("/saxton_cav/sensor_fusion/filtered/tracked_objects", ExternalObjectList, external_objects_cb)
    #rospy.Subscriber("/saxton_cav/route/route", Route, route_cb)
    # spin() simply keeps python from exiting until this node is stopped
    rospy.spin()

if __name__ == '__main__':
    try:
        echo_node()
    except rospy.ROSInterruptException:
        pass
