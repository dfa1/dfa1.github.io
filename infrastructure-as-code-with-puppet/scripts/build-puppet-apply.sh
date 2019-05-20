#!/bin/bash
CALLING_OPTIONS="$*"
OPTIONS=${CALLING_OPTIONS:-\$*}
WHAT="manifests/"

cat <<EOF
PUPPET_DATA=$(tar zcf - ${WHAT} | base64 )

rm -rf /etc/puppet/{manifests,modules}
echo -n \$PUPPET_DATA | base64 --decode | tar xz -C /etc/puppet
puppet apply --detailed-exitcodes /etc/puppet/manifests/site.pp $OPTIONS
RES="\$?"
case "\$RES" in
[02])
echo OK.
exit 0
;;

*)
echo KO.
exit 1
;;
esac
EOF
