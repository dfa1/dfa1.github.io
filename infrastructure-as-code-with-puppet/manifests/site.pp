class httpd {

    package { 'httpd':
        ensure        => installed,
        allow_virtual => false,
    }

    service { 'httpd':
        ensure    => running,
        enable    => true,
        subscribe => Package['httpd'],
    }

    file { [ "/etc/httpd", "/etc/httpd/conf.d", "/var/www" ]:
        ensure  => directory,
        require => Package['httpd'],
    }

	user { 'apache':
		ensure => present,
	}

	group { 'apache':
		ensure => present,
	}

}

class httpd::enable_named_virtual_hosts {
    file { "/etc/httpd/conf.d/001-enable-named-virtual-hosts.conf":
        ensure  => file,
        owner   => root,
        group   => root,
        mode    => 400,
        notify  => Service['httpd'],
        content => "# This file is managed by puppet
NameVirtualHost *:80
"
    }
}

define httpd::virtual_host($server_name) {
    file { "/etc/httpd/conf.d/$server_name.conf":
        ensure  => file,
        owner   => root,
        group   => root,
        mode    => 400,
        notify  => Service['httpd'],
        content => "# This file is managed by puppet
<VirtualHost *:80>
  DocumentRoot /var/www/$server_name
  ServerName $server_name
</VirtualHost>
",
    }

    file { "/var/www/$server_name":
        ensure => directory,
        owner  => 'apache',
        group  => 'apache',
        mode   => 0750,
    }

    file { "/var/www/$server_name/index.html":
        ensure => file,
        owner  => 'apache',
        group  => 'apache',
        mode   => 0750,
        content => "Hello, I'm $server_name\n",
    }

}

class ntpd {
    package { 'ntp':
        ensure        => latest,
        allow_virtual => false,
    }

    service { 'ntpd':
        ensure    => running,
        enable    => true,
        subscribe => Package['ntp'],
    }
}

node 'pinocchio' {
    include ntpd

    ssh_authorized_key { 'geppetto':
        ensure => present,
        user   => 'root',
        type   => 'ssh-rsa',
        key    => 'AAAAB3NzaC1yc2EAAAADAQABAAABAQDaTYviDJNv69jDzPWSIZFO0o+O884PmfeXiuk1OM6EAkTWPhUr1vB5ucG9ia49qnHWKi2k6uTdfMe4/GxCXXfC+F9R6GGd0lcHOvKWoNs16ZnDuZRqaG+2Hcic/t3OagT3rzXGg7uxGK3IeWGO12uteieaA2MDAqRJCBv5oAhh3Vk1+XMrMKxy/oF5KL46hgcIAAF4nFhCaMlk0T/xio7SRBif2I8IhyDmBgx2Ffag2xZwsoiTeeBzdbRgVKa3uGcFqfVtRvDfV92whB68J9DkRYsUE6AaWEAeL5NiIfiXAb9nUkjMWef/QGEZWbdz9qG2+y9bEr5egfbR0rso2vNl'
    }

    include httpd
    include httpd::enable_named_virtual_hosts

    httpd::virtual_host { 'first virtual host':
        server_name => 'cat',
    }

    httpd::virtual_host { 'second virtual host':
        server_name => 'fox',
    }
}
