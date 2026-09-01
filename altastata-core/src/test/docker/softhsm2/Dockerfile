# SoftHSM2 Docker image for AltaStata HSM development/testing
FROM ubuntu:22.04

# Install SoftHSM2 and dependencies
RUN apt-get update && apt-get install -y \
    softhsm2 \
    opensc \
    openjdk-17-jdk-headless \
    && rm -rf /var/lib/apt/lists/*

# Create directories
RUN mkdir -p /var/lib/softhsm/tokens /etc/softhsm

# Configure SoftHSM2
RUN echo "directories.tokendir = /var/lib/softhsm/tokens" > /etc/softhsm/softhsm2.conf && \
    echo "objectstore.backend = file" >> /etc/softhsm/softhsm2.conf

# Set environment
ENV SOFTHSM2_CONF=/etc/softhsm/softhsm2.conf
ENV SOFTHSM_LIB=/usr/lib/softhsm/libsofthsm2.so

# Initialize token script
COPY init-token.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/init-token.sh

# Volume for persistent token storage
VOLUME /var/lib/softhsm/tokens

# Default command - keep container running
CMD ["tail", "-f", "/dev/null"]
