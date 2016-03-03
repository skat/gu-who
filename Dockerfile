FROM java:latest  
MAINTAINER Øyvind Hansen oyvind.hansen@skat.dk 
ENV SCALA_VERSION 2.11.7  
ENV SBT_VERSION 0.13.11
ENV SBT_OPTS -Xmx2G -XX:+UseConcMarkSweepGC -XX:+CMSClassUnloadingEnabled -Xss2M -Duser.timezone=GMT  

# install sbt  
RUN wget https://dl.bintray.com/sbt/debian/sbt-$SBT_VERSION.deb  
RUN dpkg -i sbt-$SBT_VERSION.deb  

# install scala  
RUN wget https://downloads.typesafe.com/scala/$SCALA_VERSION/scala-$SCALA_VERSION.deb  
RUN dpkg -i scala-$SCALA_VERSION.deb  

# fetch base dependencies  
RUN sbt compile  
VOLUME /gu-who 
WORKDIR /gu-who  
CMD ["sbt start"]  
