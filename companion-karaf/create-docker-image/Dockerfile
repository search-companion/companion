FROM adoptopenjdk:11-jre-hotspot

# Karaf environment variables
ENV KARAF_INSTALL_PATH /opt
ENV KARAF_EXEC exec
ENV KARAF_APP_NAME karaf
ENV PATH $PATH:$KARAF_INSTALL_PATH/$KARAF_APP_NAME/bin

# karaf_dist can point to a directory or a tarball on the local system
ARG karaf_dist=NOT_SET

# Install build dependencies and karaf
ADD $karaf_dist $KARAF_INSTALL_PATH
RUN set -x && \
  ln -s $KARAF_INSTALL_PATH/companion-karaf* $KARAF_INSTALL_PATH/$KARAF_APP_NAME

EXPOSE 8101 1099 44444 8181 9999
CMD ["karaf", "run"]